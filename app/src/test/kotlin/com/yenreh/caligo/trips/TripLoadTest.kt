package com.yenreh.caligo.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.between
import com.yenreh.caligo.trips.JourneyFixtures.busOn
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.firstArrivals
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.JourneyFixtures.origin
import com.yenreh.caligo.trips.JourneyFixtures.secondArrivals
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.bus
import com.yenreh.caligo.trips.TripFixtures.out
import com.yenreh.caligo.trips.TripFixtures.plan
import com.yenreh.caligo.waits.FakeWaitSource
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a trip costs per minute on scripted rides: its notification's
 * posts and its requests, the line's buses, a ride's arrivals and its
 * waits' asks, each under a bound. Before the posts left out the same
 * content and the asks backed off, the same scripts gave per minute:
 * on foot 17.75 posts, at the stop 20, on the bus 10.25, a single ride
 * 10; with no bus listed 1 arrivals ask, and with the service down 1
 * arrivals ask and 4.2 rounds of buses.
 */
@RunWith(AndroidJUnit4::class)
class TripLoadTest {
    private val h = TripHarness()

    @After
    fun tearDown() = h.tearDown()

    private data class Load(val posts: Int, val buses: Int, val arrivals: Int, val waits: Int)

    private fun load(): Load = Load(
        h.service.posts,
        h.source.asked.size,
        h.source.arrivalsAsked.size,
        (h.app.container.waits.source as? FakeWaitSource)?.asked?.size ?: 0,
    )

    private data class Rates(val posts: Double, val buses: Double, val arrivals: Double, val waits: Double)

    private fun perMinute(before: Load, after: Load, minutes: Double) = Rates(
        (after.posts - before.posts) / minutes,
        (after.buses - before.buses) / minutes,
        (after.arrivals - before.arrivals) / minutes,
        (after.waits - before.waits) / minutes,
    )

    /** [rates] within [posts] posts and [buses], [arrivals] and [waits] requests a minute */
    private fun assertAtMost(rates: Rates, posts: Double, buses: Double, arrivals: Double = 0.0, waits: Double = 0.0) {
        val bound = Rates(posts, buses, arrivals, waits)
        assertTrue("$rates over $bound", rates.posts <= posts && rates.buses <= buses && rates.arrivals <= arrivals && rates.waits <= waits)
    }

    @Test
    fun aRide() {
        h.source.answer = { at -> listOf(bus("32051", (at - NOW) * 5.0 / 1000)) }
        h.start(plan)
        val start = load()
        h.ride(8 * MINUTE) { at -> (at - NOW) * 5.0 / 1000 + listOf(-8.0, 6.0, 0.0)[((at / 15_000) % 3).toInt()] }
        // A post at each fix and each round of buses at most: 4 now
        assertAtMost(perMinute(start, load(), 8.0), posts = 6.0, buses = 2.0)
    }

    @Test
    fun aPlannerTripWithNoBusListed() {
        h.waitsAnswering { _, at -> WaitAnswer(emptyList(), at) }
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
        val stop = GeoPoint(out[0].latitude, out[0].longitude)
        val a = load()
        h.move(10 * MINUTE) { stop }
        // At 0, 1, 3 and 7 minutes: 0.3 a minute; the waiting bar and the minutes: 2.1 posts
        assertAtMost(perMinute(a, load(), 10.0), posts = 3.0, buses = 4.2, arrivals = 0.4)
    }

    @Test
    fun aPlannerTripWithTheServiceDown() {
        h.source.arrivalsAnswer = { _, _ -> throw com.yenreh.caligo.data.datasources.NetworkApiException("offline") }
        h.source.answer = { throw com.yenreh.caligo.data.datasources.NetworkApiException("offline") }
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
        val stop = GeoPoint(out[0].latitude, out[0].longitude)
        val a = load()
        h.move(10 * MINUTE) { stop }
        // Rounds at 0, 1, 2.5, 5, 7.5 and 10 minutes; arrivals at 0, 1.5, 3.5, 6.5 and 9.5
        assertAtMost(perMinute(a, load(), 10.0), posts = 3.0, buses = 1.3, arrivals = 0.5)
    }

    @Test
    fun aPlannerTrip() {
        h.source.arrivalsAnswer = { stops, at -> if (stops.any { it.stopId == out[0].stopId }) firstArrivals(at) else secondArrivals(at) }
        h.waitsAnswering { _, at -> WaitAnswer(firstArrivals(NOW).stops + secondArrivals(NOW + 12 * MINUTE).stops, at) }
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
        val stop = GeoPoint(out[0].latitude, out[0].longitude)
        val a = load()
        // On foot at 1.2 m/s, a few metres of noise
        h.move(4 * MINUTE) { at -> between(origin, stop, (at - NOW) * 1.2 / 1000 + listOf(-4.0, 3.0, 0.0)[((at / 15_000) % 3).toInt()]) }
        val b = load()
        h.move(2 * MINUTE) { stop }
        val c = load()
        h.pressTitled("T31: I took it")
        val t31At = h.now()
        h.source.byLine = { line, at -> if (line == "T31") listOf(busOn(calle5Route, "41022", (at - t31At) * 5.0 / 1000)) else emptyList() }
        h.move(4 * MINUTE) { at -> calle5Route.pointAt((at - t31At) * 5.0 / 1000) }
        val d = load()
        // Now about 4.75, 3.5 and 4 posts a minute
        assertAtMost(perMinute(a, b, 4.0), posts = 6.0, buses = 4.0, waits = 1.5)
        assertAtMost(perMinute(b, c, 2.0), posts = 5.0, buses = 4.0, waits = 2.0)
        assertAtMost(perMinute(c, d, 4.0), posts = 6.0, buses = 2.0)
    }
}
