package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.timeTrip
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.firstArrivals
import com.yenreh.caligo.trips.JourneyFixtures.fixAt
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.JourneyFixtures.secondArrivals
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripFixtures.out
import com.yenreh.caligo.trips.TripFixtures.plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a trip gets where it goes, as the trip screen says it under its title */
class TripArrivalTest {
    /** The planner's option timed against the arrivals at both stops, as "Start trip" hands it over */
    private val live = LiveArrivals(
        NOW,
        firstArrivals(NOW).stops + secondArrivals(NOW).stops,
        answeredAt = (firstArrivals(NOW).stops + secondArrivals(NOW).stops).associate { it.id to NOW },
    )
    private val timed = timeTrip(option, start = NOW, live = live)
    private val started = Journey.planned(timed, DESTINATION, NOW)

    @Test
    fun rightAfterStartTripItIsTheTimeThePlannerShowed() {
        val arrival = checkNotNull(TripArrival.of(started, NOW))
        assertEquals(timed.arrivesAt, arrival.at)
        assertEquals(!timed.isLive, arrival.estimated)
        // Untimed by live buses, the same with the usual figures
        val rough = timeTrip(option, start = NOW)
        assertEquals(rough.arrivesAt, TripArrival.of(Journey.planned(rough, DESTINATION, NOW), NOW)?.at)
        assertEquals(true, TripArrival.of(Journey.planned(rough, DESTINATION, NOW), NOW)?.estimated)
    }

    @Test
    fun theBusAwaitedTimesTheRideAheadAndALineChosenChangesItAtOnce() {
        val waiting = started.fixed(fixAt(GeoPoint(out[0].latitude, out[0].longitude), NOW + MINUTE))
        val at = NOW + MINUTE
        val awaited = listOf(AwaitedLine("E21", at + 3 * MINUTE), AwaitedLine("T31", at + 25 * MINUTE))
        val any = checkNotNull(TripArrival.of(waiting, at, awaited))
        val t31 = checkNotNull(TripArrival.of(waiting.choosing("T31"), at, awaited))
        // The E21 in 3 catches the A47 the planner timed; the T31 in 25 misses it, and waits for the next
        assertTrue("${any.at} then ${t31.at}", t31.at >= any.at + 15 * MINUTE)
        // Both due before that A47 leaves: the same A47, the same arrival
        val early = listOf(AwaitedLine("E21", at + 3 * MINUTE), AwaitedLine("T31", at + 5 * MINUTE))
        assertEquals(TripArrival.of(waiting, at, early)?.at, TripArrival.of(waiting.choosing("T31"), at, early)?.at)
    }

    @Test
    fun onTheBusItCountsFromWhereTheRideIs() {
        val riding = started.took("T31", NOW).fixed(fixAt(calle5Route.pointAt(900.0), NOW + 2 * MINUTE))
        val at = NOW + 2 * MINUTE
        val trip = checkNotNull(riding.ride)
        val arrival = checkNotNull(TripArrival.of(riding, at))
        // The rest of this ride, then the walk to the A47, its ride and the walk to the end
        assertTrue(arrival.at > at + trip.millisTo(trip.plan.target, at))
        assertTrue(arrival.estimated)
    }

    @Test
    fun aSingleRideArrivesAtItsStopAndARideFollowedHasNoTime() {
        val ride = Journey.single(plan, NOW).let { it.copy(ride = it.ride?.fixed(fix(1210.0, NOW + 3 * MINUTE))) }
        val at = NOW + 3 * MINUTE
        val trip = checkNotNull(ride.ride)
        val arrival = checkNotNull(TripArrival.of(ride, at))
        assertEquals(at + trip.millisTo(plan.target, at), arrival.at)
        assertFalse(arrival.estimated)
        assertNull(TripArrival.of(Journey.single(plan.copy(alight = null), NOW), NOW))
        assertEquals(Trip.start(plan, NOW).plan, plan)
    }
}
