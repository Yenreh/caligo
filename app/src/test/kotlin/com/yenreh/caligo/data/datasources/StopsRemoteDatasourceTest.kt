package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.dropped
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The arrivals and catalog cases of test/stops_remote_datasource_test.dart */
class StopsRemoteDatasourceTest {
    private var service: MockService? = null

    @get:Rule
    val temporary = TemporaryFolder()

    @After
    fun close() {
        service?.close()
    }

    private fun arrivals(respond: (call: Int, url: HttpUrl) -> MockResponse) =
        MockService(respond).also { service = it }

    private fun MockService.datasource(cache: JsonCache = JsonCache.None) =
        StopsRemoteDatasource(client, cache, backoff = Duration.ZERO)

    @Test
    fun parsesNearbyStopsWithTheirArrivals() = runTest {
        val service = arrivals { _, _ -> ok(ARRIVALS_BODY) }

        val stops = service.datasource().getNearbyStops(latitude = 3.4516, longitude = -76.532)

        assertEquals(
            "$ARRIVALS_URL?latitud=3.4516&longitud=-76.532&radio=300",
            service.requested.single().toString(),
        )
        assertEquals(2, stops.size)
        assertEquals("Plaza de Cayzedo A1", stops.first().name)
        assertEquals(119.52, stops.first().distanceMeters, 0.01)
        assertEquals(2, stops.first().arrivals.size)
        assertEquals("E21", stops.first().arrivals.first().line)
        assertEquals(1787429222000, stops.first().arrivals.first().arrivalTime)
        assertEquals("Plaza de Cayzedo A1", stops.first().arrivals.first().stopName)
        assertTrue(stops.last().arrivals.isEmpty())
    }

    @Test
    fun capsTheRadiusAtWhatTheServiceAccepts() = runTest {
        val service = arrivals { _, _ -> ok("[]") }

        service.datasource().getNearbyStops(latitude = 3.4516, longitude = -76.532, radiusMeters = 5000)

        assertEquals("300", service.requested.single().queryParameter("radio"))
    }

    @Test
    fun reportsServiceLevelErrorsReturnedWithHttp200() = runTest {
        val service = arrivals { _, _ -> ok("""{"error":"El radio no puede estar vacía."}""") }

        val error = assertThrowsSuspend<ServerApiException> {
            service.datasource().getNearbyStops(latitude = 1.0, longitude = 1.0)
        }
        assertEquals("Arrivals service: El radio no puede estar vacía.", error.message)
        assertEquals("1.0", service.requested.single().queryParameter("latitud"))
    }

    @Test
    fun retriesTransientFailuresOnce() = runTest {
        val service = arrivals { call, _ -> if (call == 1) status(500, "Server Error") else ok(ARRIVALS_BODY) }

        val stops = service.datasource().getNearbyStops(latitude = 3.4516, longitude = -76.532)

        assertEquals(2, service.calls)
        assertEquals(2, stops.size)
    }

    @Test
    fun countsMinutesUntilArrival() {
        val now = 1_767_268_800_000L
        fun bus(offsetMillis: Long) = BusArrival(
            line = "T31",
            destination = "Universidades",
            arrivalTime = now + offsetMillis,
            vehicleId = "1001",
        )

        assertEquals(3, bus(150_000).minutesUntilArrival(now))
        assertEquals(0, bus(-30_000).minutesUntilArrival(now))
    }

    // FavoriteStop naming

    private val stop = FavoriteStop(
        id = "500800",
        stopId = "500800",
        name = "Plaza de Cayzedo A1",
        anchorLatitude = 3.4516,
        anchorLongitude = -76.532,
    )

    @Test
    fun showsTheRealNameWhenThereIsNoCustomOne() {
        assertEquals("Plaza de Cayzedo A1", stop.displayName)
        assertNull(stop.secondaryName)
    }

    @Test
    fun showsTheCustomNameWithTheRealOneBehindIt() {
        val renamed = stop.copy(customName = "Trabajo")

        assertEquals("Trabajo", renamed.displayName)
        assertEquals("Plaza de Cayzedo A1", renamed.secondaryName)
    }

    @Test
    fun fallsBackToTheRealNameWhenTheCustomOneIsCleared() {
        val cleared = stop.copy(customName = "Trabajo").copy(customName = null)

        assertEquals("Plaza de Cayzedo A1", cleared.displayName)
        assertNull(cleared.secondaryName)
    }

    // Stops placed on the map: locatedStops and its trilateration

    /**
     * A service that sees one stop 50 m north and 30 m east of the origin,
     * at its true distance from wherever it is asked around
     */
    private fun stopNearOrigin() = arrivals { _, url ->
        val metresPerDegree = 111320.0
        val lonScale = metresPerDegree * cos(ORIGIN_LAT * PI / 180)
        val vantageY = (url.queryParameter("latitud")!!.toDouble() - ORIGIN_LAT) * metresPerDegree
        val vantageX = (url.queryParameter("longitud")!!.toDouble() - ORIGIN_LON) * lonScale
        val distance = sqrt((30 - vantageX).pow(2) + (50 - vantageY).pow(2))
        ok("""[{"idParada":"1","nombreParada":"Target","distanciaMetros":$distance,"buses":[]}]""")
    }

    @Test
    fun placesStopsByTrilateratingThreeDistanceReadings() = runTest {
        // Seen from the origin and from two vantage points 120 m away
        val service = stopNearOrigin()
        val metresPerDegree = 111320.0
        val lonScale = metresPerDegree * cos(ORIGIN_LAT * PI / 180)

        val stops = service.datasource().getLocatedStops(ORIGIN_LAT, ORIGIN_LON)

        val stop = stops.single()
        assertTrue(stop.hasPosition)
        assertEquals(50.0, (stop.latitude!! - ORIGIN_LAT) * metresPerDegree, 1.0)
        assertEquals(30.0, (stop.longitude!! - ORIGIN_LON) * lonScale, 1.0)
    }

    @Test
    fun keepsStopsThatOnlyTheFirstReadingSaw() = runTest {
        // Only the origin sees the stops; the vantage points are empty
        val service = arrivals { call, _ -> ok(if (call == 1) ARRIVALS_BODY else "[]") }

        val stops = service.datasource().getLocatedStops(3.4516, -76.532)

        assertEquals(2, stops.size)
        assertFalse(stops.all { it.hasPosition })
    }

    @Test
    fun reusesAStopPositionInsteadOfAskingAgain() = runTest {
        val service = stopNearOrigin()
        val datasource = service.datasource()

        val first = datasource.getLocatedStops(ORIGIN_LAT, ORIGIN_LON)
        assertEquals(3, service.calls)
        assertTrue(first.single().hasPosition)

        // The position is known now, so the vantage points are skipped
        val second = datasource.getLocatedStops(ORIGIN_LAT, ORIGIN_LON)
        assertEquals(4, service.calls)
        assertTrue(second.single().hasPosition)
    }

    @Test
    fun handsOverTheStopListBeforePlacingTheStops() = runTest {
        val service = arrivals { _, _ -> ok(ARRIVALS_BODY) }

        val first = service.datasource().locatedStops(3.4516, -76.532).first()

        // Only the request around the point itself was needed for the list
        assertEquals(1, service.calls)
        assertEquals(listOf("500800", "500751"), first.map { it.id })
    }

    @Test
    fun asksTheVantagePoints120MetresNorthAndEast() = runTest {
        val service = arrivals { _, _ -> ok(ARRIVALS_BODY) }

        service.datasource().getLocatedStops(3.4516, -76.532)

        val asked = service.requested.drop(1).map {
            it.queryParameter("latitud")!!.toDouble() to it.queryParameter("longitud")!!.toDouble()
        }.toSet()
        val lonScale = 111320.0 * cos(3.4516 * PI / 180)
        assertEquals(setOf(3.4516 + 120 / 111320.0 to -76.532, 3.4516 to -76.532 + 120 / lonScale), asked)
    }

    @Test
    fun aVantagePointThatFailsLeavesTheListAsItCame() = runTest {
        val service = arrivals { call, _ -> if (call == 1) ok(ARRIVALS_BODY) else status(404) }

        val answers = service.datasource().locatedStops(3.4516, -76.532).toList()

        assertEquals(1, answers.size)
        assertEquals(2, answers.single().size)
    }

    @Test
    fun keepsThePlacedStopsForTheNextRun() = runTest {
        val service = stopNearOrigin()
        val cache = MemoryCache()
        service.datasource(cache).getLocatedStops(ORIGIN_LAT, ORIGIN_LON)

        // Saved a moment later, for a whole batch at once
        val saved = withContext(Dispatchers.Default) {
            withTimeout(WAIT_TIMEOUT_MS) {
                var kept: JsonElement? = null
                while (kept == null) {
                    kept = cache.read("stop_positions")
                    if (kept == null) delay(50)
                }
                kept
            }
        }
        val position = (saved as JsonObject)["1"] as JsonArray
        assertEquals(2, position.size)

        // A new run reads them back and asks no further
        MockService { _, _ -> ok("""[{"idParada":"1","nombreParada":"Target","distanciaMetros":58.3,"buses":[]}]""") }
            .use { next ->
                val again = StopsRemoteDatasource(next.client, cache, backoff = Duration.ZERO)
                    .getLocatedStops(ORIGIN_LAT, ORIGIN_LON)
                assertEquals(1, next.calls)
                assertTrue(again.single().hasPosition)
            }
    }

    // StopsRemoteDatasource when the service pushes back

    @Test
    fun doesNotRetryARateLimitAndRestsBeforeAskingAgain() = runTest {
        val service = arrivals { _, _ -> status(429, "Too Many Requests") }
        val datasource = service.datasource()

        assertThrowsSuspend<ServerApiException> { datasource.getNearbyStops(3.45, -76.53) }
        assertEquals(1, service.calls)

        val resting = assertThrowsSuspend<RateLimitApiException> { datasource.getNearbyStops(3.45, -76.53) }
        assertSame(ServiceGuard.RESTING, resting)
        assertEquals(1, service.calls)
    }

    @Test
    fun anOfflineDeviceIsNotTakenForAPushBack() = runTest {
        val service = arrivals { _, _ -> dropped() }
        val datasource = service.datasource()

        repeat(2) {
            assertThrowsSuspend<NetworkApiException> { datasource.getNearbyStops(3.45, -76.53) }
        }
        // Two attempts per call, and the second call still went out
        assertEquals(4, service.calls)
    }

    // ServiceGuard

    @Test
    fun doublesTheWaitWithEachPushBackInARowUpToACap() {
        var now = 1_790_000_000_000L
        val guard = ServiceGuard { now }

        fun waitAfterPushBack(): Long {
            guard.pushedBack()
            var wait = 0L
            while (true) {
                try {
                    guard.check()
                    return wait
                } catch (_: RateLimitApiException) {
                    now += 1.minutes.inWholeMilliseconds
                    wait++
                }
            }
        }

        assertEquals(listOf(1L, 2, 4, 8, 16, 16), List(6) { waitAfterPushBack() })
    }

    @Test
    fun anAnswerClearsTheWaitAtOnce() {
        val guard = ServiceGuard().apply { pushedBack() }
        try {
            guard.check()
            throw AssertionError("Expected the guard to be resting")
        } catch (_: RateLimitApiException) {
        }

        guard.answered()
        guard.check()
    }

    @Test
    fun askingForTheSamePointTwiceAtOnceSendsOneRequest() = runTest {
        val service = arrivals { _, _ ->
            MockResponse.Builder()
                .body(ARRIVALS_BODY)
                .headersDelay(200, TimeUnit.MILLISECONDS)
                .build()
        }
        val datasource = service.datasource()

        val first = async { datasource.getNearbyStops(3.45, -76.53) }
        val second = async { datasource.getNearbyStops(3.45, -76.53) }
        val answers = listOf(first.await(), second.await())

        assertEquals(1, service.calls)
        assertEquals(answers[0].map { it.id }, answers[1].map { it.id })
    }

    @Test
    fun placesTheStopsWhosePositionIsKept() = runTest {
        val service = arrivals { _, _ -> ok(ARRIVALS_BODY) }
        val cache = MemoryCache(
            "stop_positions" to """{"500800": [3.452428, -76.5313133], "bad": [1], "500751": "x"}""",
        )
        val datasource = service.datasource(cache)

        val stops = datasource.getNearbyStops(3.4516, -76.532)

        assertEquals(3.452428, stops.first().latitude!!, 0.0)
        assertEquals(-76.5313133, stops.first().longitude!!, 0.0)
        assertFalse(stops.last().hasPosition)
        assertEquals(
            mapOf("500800" to GeoPoint(3.452428, -76.5313133)),
            datasource.stopPositions(listOf("500800", "500751", "bad", "none")),
        )
    }

    // Arrivals for a trip

    private fun lineStop(id: String, lat: Double, lon: Double) =
        LineStop(stopId = id, name = id, latitude = lat, longitude = lon, direction = 0, sequence = 1)

    @Test
    fun asksOnceForStopsCloseTogetherThenReusesIt() = runTest {
        val service = arrivals { _, _ -> ok(ARRIVALS_BODY) }
        val datasource = service.datasource()
        // About 100 m apart, and a third one 2 km away
        val stops = listOf(
            lineStop("500800", 3.4516, -76.5320),
            lineStop("500751", 3.4525, -76.5320),
            lineStop("far", 3.4700, -76.5320),
        )

        val first = datasource.arrivalsFor(stops)
        assertEquals(2, service.calls)
        assertTrue(first.stops.any { it.id == "500800" })
        assertFalse(first.failed)

        datasource.arrivalsFor(stops.take(2))
        assertEquals(2, service.calls)

        datasource.arrivalsFor(stops.take(2), maxAge = Duration.ZERO)
        assertEquals(3, service.calls)
    }

    @Test
    fun saysSoWhenAnAreaCannotBeAskedAbout() = runTest {
        val service = arrivals { _, _ -> status(500) }

        val live = service.datasource().arrivalsFor(listOf(lineStop("500800", 3.4516, -76.5320)))

        assertTrue(live.stops.isEmpty())
        assertTrue(live.failed)
        // Not answered: nothing it lists can be taken to have left
        assertTrue(live.answeredAt.isEmpty())
    }

    @Test
    fun aStopAskedAboutCountsAsAnsweredEvenWhenLeftOut() = runTest {
        val service = arrivals { _, _ -> ok(ARRIVALS_BODY) }
        val datasource = service.datasource()
        // Within reach of the area, but not in its answer: no bus coming
        val quiet = lineStop("quiet", 3.4525, -76.5320)

        val live = datasource.arrivalsFor(listOf(lineStop("500800", 3.4516, -76.5320), quiet))

        assertTrue(live.stops.none { it.id == "quiet" })
        assertTrue("quiet" in live.answeredAt)
        assertEquals(live.answeredAt.getValue("quiet"), live.answeredAt.getValue("500800"))
        // A reused area is the same answer, fetched when it was
        val again = datasource.arrivalsFor(listOf(quiet))
        assertEquals(1, service.calls)
        assertEquals(live.answeredAt.getValue("quiet"), again.answeredAt.getValue("quiet"))
    }

    @Test
    fun asksOnceBetweenTwoStopsTooFarApartToAnswerForEachOther() = runTest {
        val service = arrivals { _, _ -> ok("[]") }

        // About 450 m apart: each is out of the other's reach, not the middle's
        service.datasource().arrivalsFor(
            listOf(lineStop("a", 3.4500, -76.5320), lineStop("b", 3.4540, -76.5320)),
        )

        assertEquals(1, service.calls)
        assertEquals(3.4520, service.requested.single().queryParameter("latitud")!!.toDouble(), 1e-9)
    }

    // The catalog: stations and the lines of a stop

    @Test
    fun parsesTheLinesServingAStop() = runTest {
        val service = arrivals { _, url ->
            assertTrue(url.encodedPath.endsWith("/linesByStop/512081"))
            ok("""[{"lineId":282,"shortName":"P82","name":"TERMINAL CALIPSO"},{"shortName":"","name":"X"}]""")
        }

        val lines = service.datasource().getLinesByStop("512081")

        assertEquals(1, lines.size)
        assertEquals("P82", lines.first().shortName)
        assertEquals("TERMINAL CALIPSO", lines.first().name)
        assertEquals(
            "https://wsmio.siur.com.co:8083/apiMIO/jaxrs/linesByStop/512081",
            service.requested.single().toString(),
        )
    }

    @Test
    fun parsesTheStationCatalog() = runTest {
        val service = arrivals { _, _ -> ok(STATIONS_BODY) }

        val stations = service.datasource().getStations()

        assertEquals(1, stations.size)
        assertEquals("Estación Alamos", stations.first().name)
        assertEquals("Av 3N - Cl 52N", stations.first().address)
        assertEquals(3.484246, stations.first().latitude, 0.000001)
    }

    @Test
    fun servesTheStationCatalogFromCacheOnTheSecondCall() = runTest {
        val service = arrivals { _, _ -> ok(STATIONS_BODY) }
        val cache = FileJsonCache(temporary.newFolder("cache"))
        val datasource = service.datasource(cache)

        datasource.getStations()
        val second = datasource.getStations()

        assertEquals(1, service.calls)
        assertEquals("Estación Alamos", second.single().name)
    }

    @Test
    fun anOldCopyAnswersWhenTheCatalogCannotBeReached() = runTest {
        val folder = temporary.newFolder("cache")
        // Saved eight days ago: past the catalog's week
        val old = FileJsonCache(folder, now = { System.currentTimeMillis() - 8L * 24 * 3600 * 1000 })
        old.write("stations", parseJson(STATIONS_BODY))
        val service = arrivals { _, _ -> dropped() }

        val stations = service.datasource(FileJsonCache(folder)).getStations()

        assertEquals(listOf("Estación Alamos"), stations.map { it.name })
        assertTrue(service.calls > 0)
    }

    @Test
    fun anOldCopyIsAskedForAgainWhenTheCatalogAnswers() = runTest {
        val folder = temporary.newFolder("cache")
        val old = FileJsonCache(folder, now = { System.currentTimeMillis() - 8L * 24 * 3600 * 1000 })
        old.write("stations", parseJson("[]"))
        val service = arrivals { _, _ -> ok(STATIONS_BODY) }

        val stations = service.datasource(FileJsonCache(folder)).getStations()

        assertEquals(1, service.calls)
        assertEquals(1, stations.size)
    }

    @Test
    fun withoutACopyACatalogFailureIsTheCallersToHandle() = runTest {
        val service = arrivals { _, _ -> status(404) }

        assertThrowsSuspend<ServerApiException> { service.datasource().getStations() }
    }

    /** A cache in memory, filled with JSON texts by key */
    private class MemoryCache(vararg entries: Pair<String, String>) : JsonCache {
        // Written in the background, polled from the test's thread
        private val values: MutableMap<String, JsonElement> =
            ConcurrentHashMap(entries.associate { (key, text) -> key to parseJson(text) })

        override suspend fun read(key: String, maxAge: Duration?): JsonElement? = values[key]

        override suspend fun readEntry(key: String, remember: Boolean) = values[key]?.let { CacheEntry(System.currentTimeMillis(), it) }

        override suspend fun write(key: String, value: JsonElement, remember: Boolean) {
            values[key] = value
        }

        override suspend fun prune(maxAge: Duration) = Unit
    }

    private companion object {
        const val STATIONS_BODY = """
[{"id":1,"name":"Estación Alamos","address":"Av 3N - Cl 52N",
  "neighborhood":"Ciudad Los Alamos","commune":2,
  "longitude":-76.5133880103,"latitude":3.4842460459}]"""

        const val ORIGIN_LAT = 3.4842
        const val ORIGIN_LON = -76.5134

        const val ARRIVALS_URL =
            "https://servicios.siur.com.co/buscarutas/api/paradas_con_buses_proximos_a_llegar.php"

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
