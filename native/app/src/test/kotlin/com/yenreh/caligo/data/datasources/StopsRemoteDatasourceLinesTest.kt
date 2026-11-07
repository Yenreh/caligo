package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.dropped
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.domain.entities.LineHours
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line cases of test/stops_remote_datasource_test.dart: the catalog,
 * the routes, the buses and their directions, the hours, the copies kept
 * offline, and the stops the routes place
 */
class StopsRemoteDatasourceLinesTest {
    private var service: MockService? = null

    @After
    fun close() {
        service?.close()
    }

    private fun answering(respond: (call: Int, url: HttpUrl) -> MockResponse) =
        MockService(respond).also { service = it }

    /** Each path ending as given answers its body; anything else is not found */
    private fun serving(vararg bodies: Pair<String, String>) = answering { _, url ->
        bodies.firstOrNull { (end, _) -> url.encodedPath.endsWith(end) }?.let { ok(it.second) } ?: status(404, "Not Found")
    }

    private fun MockService.datasource(cache: JsonCache = JsonCache.None, now: () -> Long = System::currentTimeMillis) =
        StopsRemoteDatasource(client, cache, backoff = Duration.ZERO, now = now)

    /** Wait on the real clock, as the background work runs on its own threads */
    private suspend fun eventually(check: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (!check()) delay(20)
        }
    }

    // Line lookup

    @Test
    fun readsTheLinesSortedByName() = runTest {
        val lines = serving(
            "/lines" to """[{"lineId":347,"name":"A47"},{"lineId":152,"name":"T52"},{"lineId":302,"name":"A02"}]""",
        ).datasource().getLines()

        assertEquals(listOf("A02", "A47", "T52"), lines.map { it.name })
        assertEquals(302, lines.first().id)
    }

    @Test
    fun leavesOutLinesWithoutANumberOrAName() = runTest {
        val lines = serving(
            "/lines" to """[{"lineId":"347","name":"A47"},{"lineId":152,"name":52},{"lineId":302,"name":"A02"},"x"]""",
        ).datasource().getLines()

        assertEquals(listOf("A02"), lines.map { it.name })
    }

    @Test
    fun readsARouteInOrderEachDirectionApart() = runTest {
        val service = serving(
            "/linestops/A47" to """[
                {"orientation":"1","stopSequence":1,"stopId":"9","stopNam":"Back","longitude":-76.47,"latitude":3.41},
                {"orientation":"0","stopSequence":2,"stopId":"514484","stopNam":"Tv 103 con Kr 28F","longitude":-76.4798,"latitude":3.4109},
                {"orientation":"0","stopSequence":1,"stopId":"504107","stopNam":"Aguablanca B3","longitude":-76.4812,"latitude":3.4141}]""",
        )

        val stops = service.datasource().getLineStops("A47")

        assertEquals(
            listOf(Triple(0, 1, "504107"), Triple(0, 2, "514484"), Triple(1, 1, "9")),
            stops.map { Triple(it.direction, it.sequence, it.stopId) },
        )
        assertEquals("Aguablanca B3", stops.first().name)
        assertEquals(
            "https://wsmio.siur.com.co:8083/apiMIO/jaxrs/linestops/A47",
            service.requested.single().toString(),
        )
    }

    @Test
    fun aRouteStopTakesStopNameWhenStopNamIsMissingAndDefaultsTheRest() = runTest {
        val stops = serving(
            "/linestops/A47" to """[
                {"stopNam":null,"stopName":"Named","longitude":-76.47,"latitude":3.41},
                {"orientation":"x","stopId":7,"longitude":-76.48,"latitude":3.42},
                {"stopId":"far","longitude":"-76.49","latitude":3.43}]""",
        ).datasource().getLineStops("A47")

        assertEquals(listOf("Named", ""), stops.map { it.name })
        assertEquals(listOf(0, 0), stops.map { it.direction })
        assertEquals(listOf(0, 0), stops.map { it.sequence })
        assertEquals(listOf("", "7"), stops.map { it.stopId })
    }

    @Test
    fun aLineNameIsEncodedAsDartEncodesAUriComponent() = runTest {
        val service = serving()

        assertThrowsSuspend<ServerApiException> { service.datasource().getLineBuses("A 1/ñ") }

        assertEquals(
            "https://wsmio.siur.com.co:8083/apiMIO/jaxrs/operations/A%201%2F%C3%B1",
            service.requested.first().toString(),
        )
    }

    @Test
    fun placesTheBusesFromTheirScaledGpsReadings() = runTest {
        val buses = serving(
            "/operations/A47" to """[{"busNumber":32051,"gpsx":-7.6473702E8,"gpsy":3.4096152E7}]""",
        ).datasource().getLineBuses("A47")

        assertEquals("32051", buses.single().busNumber)
        assertEquals(3.4096152, buses.single().latitude, 1e-9)
        assertEquals(-76.473702, buses.single().longitude, 1e-9)
        // Its direction could not be learned: the service did not answer
        assertNull(buses.single().direction)
    }

    @Test
    fun anAnswerThatIsNotAListOfBusesIsAServerError() = runTest {
        val error = assertThrowsSuspend<ServerApiException> {
            serving("/operations/A47" to """{"error":"x"}""").datasource().getLineBuses("A47")
        }
        assertEquals("Unexpected buses response", error.message)
    }

    @Test
    fun givesEachBusTheDirectionOfItsTripAskingOncePerBus() = runTest {
        val infoCalls = AtomicInteger()
        val service = answering { _, url ->
            val path = url.encodedPath
            when {
                path.endsWith("/operations/A47") -> ok(
                    """[{"busNumber":32051,"gpsx":-7.648E8,"gpsy":3.441E7},{"busNumber":12081,"gpsx":-7.646E8,"gpsy":3.425E7}]""",
                )
                "/busInfo/" in path -> {
                    infoCalls.incrementAndGet()
                    val bus = path.substringAfterLast('/')
                    val orientation = if (bus == "32051") "1" else "0"
                    ok("""[{"busNumber":$bus,"line":"A47","orientation":"$orientation"}]""")
                }
                else -> status(404, "Not Found")
            }
        }
        val datasource = service.datasource()

        val buses = datasource.getLineBuses("A47")
        datasource.getLineBuses("A47")

        assertEquals(mapOf("32051" to 1, "12081" to 0), buses.associate { it.busNumber to it.direction })
        // The second round found both directions still fresh
        assertEquals(2, infoCalls.get())
    }

    @Test
    fun aBusOnAnotherLineGetsNoDirectionAndIsAskedAgain() = runTest {
        val infoCalls = AtomicInteger()
        val service = answering { _, url ->
            val path = url.encodedPath
            when {
                path.endsWith("/operations/A47") -> ok("""[{"busNumber":1,"gpsx":-7.648E8,"gpsy":3.441E7}]""")
                "/busInfo/" in path -> {
                    infoCalls.incrementAndGet()
                    ok("""[{"busNumber":1,"line":"T31","orientation":"1"}]""")
                }
                else -> status(404, "Not Found")
            }
        }
        val datasource = service.datasource()

        assertNull(datasource.getLineBuses("A47").single().direction)
        datasource.getLineBuses("A47")

        assertEquals(2, infoCalls.get())
    }

    @Test
    fun asksTheDirectionsFourAtATime() = runTest {
        val atOnce = AtomicInteger()
        val most = AtomicInteger()
        val service = answering { _, url ->
            val path = url.encodedPath
            when {
                path.endsWith("/operations/A47") -> ok(
                    (1..9).joinToString(",", "[", "]") { """{"busNumber":$it,"gpsx":-7.648E8,"gpsy":3.441E7}""" },
                )
                "/busInfo/" in path -> {
                    val now = atOnce.incrementAndGet()
                    most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    Thread.sleep(200)
                    atOnce.decrementAndGet()
                    ok("""[{"line":"A47","orientation":"0"}]""")
                }
                else -> status(404, "Not Found")
            }
        }

        val buses = service.datasource().getLineBuses("A47")

        assertEquals(9, buses.count { it.direction == 0 })
        assertTrue("${most.get()} at once", most.get() in 2..4)
    }

    @Test
    fun readsTheHoursOfEachLine() = runTest {
        val hours = serving(
            "/linesOperation" to """[{"line":"A12A","startTime":"04:23:00","endTime":"00:19:00"},
                {"line":"T31","startTime":"5","endTime":"23:00:00"},{"line":7,"startTime":"05:00","endTime":"06:00"}]""",
        ).datasource().getLineHours()

        assertEquals(setOf("A12A"), hours.keys)
        assertEquals(4.hours + 23.minutes, hours.getValue("A12A").start)
        assertEquals(19.minutes, hours.getValue("A12A").end)
    }

    @Test
    fun aLineRunningPastMidnightStillRunsJustBeforeItsEnd() {
        val late = LineHours(start = 4.hours + 23.minutes, end = 19.minutes)

        assertTrue(late.runsAt(0, 10))
        assertTrue(late.runsAt(23, 50))
        assertFalse(late.runsAt(2, 0))
    }

    @Test
    fun aDaytimeLineRunsFromItsStartToItsEndBothIncluded() {
        val day = LineHours(start = 5.hours, end = 20.hours + 9.minutes)

        assertFalse(day.runsAt(4, 59))
        assertTrue(day.runsAt(5, 0))
        assertTrue(day.runsAt(20, 9))
        assertFalse(day.runsAt(20, 10))
    }

    // Catalog kept offline

    @Test
    fun anOldCopyAnswersWhenTheCatalogCannotBeReached() = runTest {
        val cache = OldCache("lines_hours" to """[{"line":"A12A","startTime":"04:23:00","endTime":"00:19:00"}]""")
        val service = answering { _, _ -> dropped() }

        val hours = service.datasource(cache).getLineHours()

        assertEquals(listOf("A12A"), hours.keys.toList())
    }

    @Test
    fun anOldRouteAnswersAtOnceAndANewOneComesBehindIt() = runTest {
        fun route(name: String) =
            """[{"orientation":"0","stopSequence":1,"stopId":"1","stopNam":"$name","longitude":-76.53,"latitude":3.45}]"""
        val cache = OldCache("linestops_A47" to route("Old"))
        val service = answering { _, _ -> ok(route("New")) }
        val datasource = service.datasource(cache)

        val stops = datasource.getLineStops("A47", staleOk = true)
        assertEquals("Old", stops.single().name)

        eventually { "linestops_A47" in cache.written }
        assertEquals(1, service.calls)
        assertEquals("New", datasource.getLineStops("A47").single().name)
        assertEquals(1, service.calls)
        // A route is read once a run: not held in memory
        assertEquals(listOf(false), cache.remembered["linestops_A47"])
    }

    @Test
    fun oldRoutesAreFetchedAgainTwoAtATime() = runTest {
        fun route(name: String) =
            """[{"orientation":"0","stopSequence":1,"stopId":"$name","stopNam":"$name","longitude":-76.53,"latitude":3.45}]"""
        val names = listOf("A1", "A2", "A3", "A4", "A5")
        val cache = OldCache(*names.map { "linestops_$it" to route(it) }.toTypedArray())
        val atOnce = AtomicInteger()
        val most = AtomicInteger()
        val service = answering { _, url ->
            most.accumulateAndGet(atOnce.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(200)
            atOnce.decrementAndGet()
            ok(route(url.encodedPath.substringAfterLast('/')))
        }
        val datasource = service.datasource(cache)

        for (name in names) datasource.getLineStops(name, staleOk = true)

        eventually { names.all { "linestops_$it" in cache.written } }
        assertEquals(5, service.calls)
        assertTrue("${most.get()} at once", most.get() <= 2)
    }

    @Test
    fun anOldCatalogOfLinesAnswersAtOnceWhenStaleIsFine() = runTest {
        val cache = OldCache("lines" to """[{"lineId":131,"name":"T31"}]""")
        val service = answering { _, url ->
            MockResponse.Builder()
                .body(if (url.encodedPath.endsWith("/lines")) """[{"lineId":131,"name":"T31"},{"lineId":152,"name":"T52"}]""" else "[]")
                .headersDelay(100, TimeUnit.MILLISECONDS)
                .build()
        }
        val datasource = service.datasource(cache)

        assertEquals(listOf("T31"), datasource.getLines(staleOk = true).map { it.name })

        eventually { "lines" in cache.written }
        assertEquals(listOf("T31", "T52"), datasource.getLines().map { it.name })
    }

    // The routes place the stops; lines seen running join the catalog

    @Test
    fun placesTheStopsFromTheRoutesAskingAroundNoFurther() = runTest {
        val arrivalsRequests = AtomicInteger()
        val service = answering { _, url ->
            if (url.encodedPath.endsWith("/linestops/E21")) {
                ok(
                    """[{"orientation":"0","stopSequence":1,"stopId":"500800","stopNam":"Plaza de Cayzedo A1","longitude":-76.5331,"latitude":3.4513},
                    {"orientation":"0","stopSequence":2,"stopId":"500751","stopNam":"La Ermita A2","longitude":-76.5310,"latitude":3.4531}]""",
                )
            } else {
                arrivalsRequests.incrementAndGet()
                ok(ARRIVALS_BODY)
            }
        }
        val datasource = service.datasource()
        datasource.getLineStops("E21")

        val stops = datasource.getLocatedStops(3.4516, -76.532)

        assertEquals(1, arrivalsRequests.get())
        assertEquals(3.4513, stops.first().latitude!!, 0.0)
        assertEquals(-76.5310, stops.last().longitude!!, 0.0)
    }

    @Test
    fun addsALineTheCatalogLeavesOutOnceItIsSeenRunning() = runTest {
        val service = answering { _, url ->
            val path = url.encodedPath
            when {
                path.endsWith("/lines") -> ok("""[{"lineId":131,"name":"T31"}]""")
                path.endsWith("/linesOperation") -> ok(
                    """[{"line":"T31","startTime":"04:30:00","endTime":"23:00:00"},
                    {"line":"A52","startTime":"05:00:00","endTime":"20:09:00"},
                    {"line":"E51","startTime":"05:00:00","endTime":"20:55:00"}]""",
                )
                else -> ok(
                    """[{"idParada":"1","nombreParada":"Stop","distanciaMetros":10,
                    "buses":[{"nombreLinea":"A52","nombreDestino":"Terminal","tiempoEstimadoDeSalida":1787429222000,"vehiculoId":"9"}]}]""",
                )
            }
        }
        val datasource = service.datasource()

        assertEquals(listOf("T31"), datasource.getLines().map { it.name })

        datasource.getNearbyStops(3.45, -76.53)
        // The arrivals teach what they show in the background; E51 has
        // hours too, but nothing shows it running
        eventually { datasource.getLines().map { it.name } == listOf("A52", "T31") }
        val added = datasource.getLines().first()
        assertEquals(0, added.id)
    }

    @Test
    fun asksABusItsDirectionAgainOnlyAsItNearsItsTripEnd() = runTest {
        var clock = 1_790_000_000_000L
        val asked = CopyOnWriteArrayList<String>()
        val service = answering { _, url ->
            val path = url.encodedPath
            when {
                // One direction, north from 3.40 to its last stop at 3.44
                path.endsWith("/linestops/A47") -> ok(
                    """[{"orientation":"0","stopSequence":1,"stopId":"s","stopNam":"Start","longitude":-76.53,"latitude":3.40},
                    {"orientation":"0","stopSequence":2,"stopId":"e","stopNam":"End","longitude":-76.53,"latitude":3.44}]""",
                )
                // Bus 1 is 200 m from the last stop, bus 2 four kilometres
                path.endsWith("/operations/A47") -> ok(
                    """[{"busNumber":1,"gpsx":-7.653E8,"gpsy":3.4382E7},{"busNumber":2,"gpsx":-7.653E8,"gpsy":3.40E7}]""",
                )
                "/busInfo/" in path -> {
                    val bus = path.substringAfterLast('/')
                    asked.add(bus)
                    ok("""[{"busNumber":$bus,"line":"A47","orientation":"0"}]""")
                }
                else -> status(404, "Not Found")
            }
        }
        val datasource = service.datasource(now = { clock })

        datasource.getLineBuses("A47")
        assertEquals(setOf("1", "2"), asked.toSet())

        clock += 3.minutes.inWholeMilliseconds
        asked.clear()
        datasource.getLineBuses("A47")
        assertEquals(listOf("1"), asked.toList())

        clock += 43.minutes.inWholeMilliseconds
        asked.clear()
        datasource.getLineBuses("A47")
        assertEquals(setOf("1", "2"), asked.toSet())
    }

    @Test
    fun withoutTheRouteEveryBusIsAskedAgainAfterTenMinutes() = runTest {
        var clock = 1_790_000_000_000L
        val asked = CopyOnWriteArrayList<String>()
        val service = answering { _, url ->
            val path = url.encodedPath
            when {
                path.endsWith("/operations/A47") -> ok("""[{"busNumber":1,"gpsx":-7.653E8,"gpsy":3.4382E7}]""")
                "/busInfo/" in path -> {
                    asked.add(path.substringAfterLast('/'))
                    ok("""[{"line":"A47","orientation":"0"}]""")
                }
                else -> status(404, "Not Found")
            }
        }
        val datasource = service.datasource(now = { clock })

        datasource.getLineBuses("A47")
        clock += 9.minutes.inWholeMilliseconds
        datasource.getLineBuses("A47")
        assertEquals(1, asked.size)

        clock += 2.minutes.inWholeMilliseconds
        datasource.getLineBuses("A47")
        assertEquals(2, asked.size)
    }

    /**
     * Cache holding copies saved long ago, as a week offline leaves them;
     * what is written reads back as saved now. Records whether each write
     * was to be remembered.
     */
    private class OldCache(vararg old: Pair<String, String>) : JsonCache {
        private val old = old.associate { (key, text) -> key to parseJson(text) }
        val written: MutableMap<String, JsonElement> = ConcurrentHashMap()
        val remembered: MutableMap<String, List<Boolean>> = ConcurrentHashMap()

        override suspend fun read(key: String, maxAge: Duration?): JsonElement? = written[key] ?: old[key]

        override suspend fun readEntry(key: String, remember: Boolean): CacheEntry? {
            written[key]?.let { return CacheEntry(System.currentTimeMillis(), it) }
            return old[key]?.let { CacheEntry(LONG_AGO, it) }
        }

        override suspend fun write(key: String, value: JsonElement, remember: Boolean) {
            written[key] = value
            remembered.merge(key, listOf(remember)) { a, b -> a + b }
        }

        override suspend fun prune(maxAge: Duration) = Unit
    }

    private companion object {
        /** 2020-01-01 */
        const val LONG_AGO = 1_577_836_800_000L

        const val ARRIVALS_BODY = """
[{"idParada":"500800","nombreParada":"Plaza de Cayzedo A1",
  "distanciaMetros":119.52,
  "buses":[{"nombreLinea":"E21","nombreDestino":"Est. Universidades",
            "tiempoEstimadoDeSalida":1787429222000,"vehiculoId":"637001"},
           {"nombreLinea":"P27D","nombreDestino":"Av. Roosevelt-Capri",
            "tiempoEstimadoDeSalida":1787429500000,"vehiculoId":"1144001"}]},
 {"idParada":"500751","nombreParada":"La Ermita A2",
  "distanciaMetros":187.46,"buses":[]}]"""
    }
}
