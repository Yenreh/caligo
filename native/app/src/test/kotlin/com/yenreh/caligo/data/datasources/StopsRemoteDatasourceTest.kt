package com.yenreh.caligo.data.datasources

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
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arrivals cases of test/stops_remote_datasource_test.dart */
class StopsRemoteDatasourceTest {
    private var service: MockService? = null

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

    /** A cache in memory, filled with JSON texts by key */
    private class MemoryCache(vararg entries: Pair<String, String>) : JsonCache {
        private val values = entries.associate { (key, text) -> key to parseJson(text) }.toMutableMap()

        override suspend fun read(key: String, maxAge: Duration?): JsonElement? = values[key]

        override suspend fun write(key: String, value: JsonElement) {
            values[key] = value
        }

        override suspend fun prune(maxAge: Duration) = Unit
    }

    private companion object {
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
