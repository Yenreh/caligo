package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.fixAt
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.out
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rows of a ride's lines at its stop, as the trip heard of them */
class RideBusesTest {
    /** At the stop, Plaza de Cayzedo A1, a platform of its station */
    private val waiting = Journey.planned(option, DESTINATION, NOW - MINUTE)
        .fixed(fixAt(GeoPoint(out[0].latitude, out[0].longitude), NOW))

    private fun bus(line: String, vehicle: String, minutes: Int, stop: String = out[0].name) =
        BusArrival(line, "Universidades", NOW + minutes * MINUTE, vehicle, stop)

    @Test
    fun theBusAwaitedAtAnotherPlatformIsInItsLinesRowAndNoOtherBusOfThatPlatform() {
        // The T31 listed only at platform B2, and seen later where the ride gets off; B2's other T31 runs the other way
        val b2 = NearbyStop(
            "500801",
            "Plaza de Cayzedo B2",
            40.0,
            listOf(bus("T31", "v9", 4, "Plaza de Cayzedo B2"), bus("T31", "back", 6, "Plaza de Cayzedo B2")),
            out[0].latitude + 0.0004,
            out[0].longitude,
        )
        val live = LiveArrivals(
            NOW,
            listOf(
                NearbyStop(out[0].stopId, out[0].name, 20.0, listOf(bus("E21", "e1", 3))),
                b2,
                NearbyStop(out[6].stopId, out[6].name, 20.0, listOf(bus("T31", "v9", 10, out[6].name))),
            ),
            answeredAt = mapOf(out[0].stopId to NOW, "500801" to NOW, out[6].stopId to NOW),
        )
        val candidates = waiting.pendingCandidates
        val awaited = TripEngine.awaitedBuses(live, candidates, NOW)
        assertEquals(setOf("e1", "v9"), awaited.map { it.bus.vehicleId }.toSet())

        val lines = RideBuses(checkNotNull(waiting.pendingRide)).awaiting(awaited, candidates, live).lines(waiting, NOW)
        assertEquals(listOf(3), lines.single { it.line == "E21" }.times.map { it.minutes })
        // The awaited T31 shows as its time, not "no bus coming"; the one the other way does not
        assertEquals(listOf(4), lines.single { it.line == "T31" }.times.map { it.minutes })
    }
}
