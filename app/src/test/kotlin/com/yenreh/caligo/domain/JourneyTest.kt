package com.yenreh.caligo.domain

import com.yenreh.caligo.trips.JourneyFixtures
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.a47Route
import com.yenreh.caligo.trips.JourneyFixtures.busOn
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.fixAt
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.out
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A planner trip followed leg by leg: walks, the line taken among several, changes, the end */
class JourneyTest {
    private val journey = Journey.planned(option, DESTINATION, NOW)
    private val stop = GeoPoint(out[0].latitude, out[0].longitude)

    @Test
    fun itStartsOnTheWalkToTheFirstStop() {
        assertEquals(5, journey.legs.size)
        val phase = journey.phase(NOW) as JourneyPhase.Walking
        assertEquals("Plaza de Cayzedo A1", phase.leg.toName)
        assertTrue(phase.estimated)
        assertEquals(390.0, phase.meters, 1.0)
        assertEquals(1, journey.pendingRide)
        assertEquals(listOf("E21", "T31"), (journey.legs[1] as JourneyLeg.Ride).candidates.map { it.line })
    }

    @Test
    fun fixesMeasureTheWalkLeft() {
        val walking = journey.fixed(fixAt(JourneyFixtures.between(JourneyFixtures.origin, stop, 200.0), NOW + 3 * MINUTE))
        val phase = walking.phase(NOW + 3 * MINUTE) as JourneyPhase.Walking
        assertEquals(false, phase.estimated)
        // 100 m in a straight line, 130 m on foot at 1 m/s
        assertEquals(130.0, phase.meters, 2.0)
        assertEquals(130_000.0, phase.millis.toDouble(), 2_000.0)
    }

    @Test
    fun atTheStopItWaitsForAnyOfTheLines() {
        val there = journey.fixed(fixAt(stop, NOW + 5 * MINUTE))
        assertEquals(1, there.index)
        assertTrue(there.phase(NOW + 5 * MINUTE) is JourneyPhase.Waiting)
        assertTrue(there.awaits("T31", out[0].stopId))
        assertTrue(!there.awaits("A47", out[0].stopId))
    }

    @Test
    fun withoutFixesTheEstimateWalksTheRiderToTheStop() {
        assertTrue(journey.phase(NOW + 7 * MINUTE) is JourneyPhase.Waiting)
    }

    @Test
    fun iTookItOnALineFollowsThatLine() {
        val taken = journey.took("T31", NOW + 6 * MINUTE)
        val ride = checkNotNull(taken.ride)
        assertEquals("T31", ride.plan.line)
        assertEquals(1, taken.index)
        assertEquals(6, ride.plan.alight)
        assertNull(taken.pendingRide)
        // Not a line of the ride: nothing
        assertEquals(journey, journey.took("A47", NOW + 6 * MINUTE))
    }

    /** At the stop, then two fixes 15 s apart at [speed] m/s along Calle 5 */
    private fun leaving(speed: Double, from: Journey = journey.fixed(fixAt(stop, NOW + 5 * MINUTE))): Journey {
        val a = from.fixed(fixAt(calle5Route.pointAt(100.0), NOW + 6 * MINUTE))
        return a.fixed(fixAt(calle5Route.pointAt(100.0 + speed * 15), NOW + 6 * MINUTE + 15_000))
    }

    @Test
    fun leavingTheStopAtABussPaceBesideOneOfItsLinesTakesThatLine() {
        val moving = leaving(8.0)
        val taken = moving.withBuses("T31", listOf(busOn(calle5Route, "41022", 230.0)), NOW + 6 * MINUTE + 20_000)
        assertEquals("T31", taken.ride?.plan?.line)
        assertEquals("41022", taken.ride?.bus?.busNumber)
        assertEquals(220.0, taken.ride!!.confirmed, 5.0)
    }

    @Test
    fun walkingAwayAtAWalkersPaceTakesNothing() {
        val strolling = leaving(1.2)
        assertNull(strolling.withBuses("T31", listOf(busOn(calle5Route, "41022", 120.0)), NOW + 6 * MINUTE + 20_000).ride)
    }

    @Test
    fun noBusOfTheLineBesideTheRiderTakesNothing() {
        val moving = leaving(8.0)
        // The T31's only bus is a kilometre behind; the A47 is no line of this ride
        assertNull(moving.withBuses("T31", listOf(busOn(calle5Route, "41022", 0.0).copy(latitude = 3.44)), NOW + 6 * MINUTE + 20_000).ride)
        assertNull(moving.withBuses("A47", listOf(busOn(calle5Route, "33001", 230.0)), NOW + 6 * MINUTE + 20_000).ride)
    }

    /** Riding the T31 to its last stop, a fix every 15 s at 7 m/s */
    private fun rideToTheChange(from: Journey): Pair<Journey, JourneyStep> {
        var j = from
        var at = NOW + 6 * MINUTE
        var step: JourneyStep = JourneyStep.Goes(j)
        while (at < NOW + 15 * MINUTE) {
            val along = (at - NOW - 6 * MINUTE) * 7.0 / 1000
            j = j.fixed(fixAt(calle5Route.pointAt(along), at))
            step = j.step(at)
            if (step is JourneyStep.Goes && step.journey.index != j.index) return step.journey to step
            if (step is JourneyStep.Goes) j = step.journey
            at += 15_000
        }
        return j to step
    }

    @Test
    fun aRideReachingItsStopMovesOnToTheWalkToTheNextOne() {
        val (next, _) = rideToTheChange(journey.took("T31", NOW + 6 * MINUTE))
        assertEquals(2, next.index)
        val phase = next.phase(next.legStartedAt) as JourneyPhase.Walking
        assertEquals("Kr 22 con Cl 10", phase.leg.toName)
        assertEquals(3, next.pendingRide)
    }

    @Test
    fun aRideThatEndsOtherwiseThanAtItsStopEndsTheWholeTrip() {
        // On the T31, then good fixes 500 m north of Calle 5 from 7 min on
        var j = journey.took("T31", NOW + 6 * MINUTE)
        val off = GeoPoint(stop.latitude + 500 / 111320.0, stop.longitude)
        for (at in NOW + 7 * MINUTE..NOW + 11 * MINUTE step 15_000) j = j.fixed(fixAt(off, at))
        assertEquals(JourneyStep.Ends(TripEnding.OFF_ROUTE), j.step(NOW + 11 * MINUTE))
    }

    @Test
    fun aFirstWalkOfAFewStepsIsSkipped() {
        fun from(meters: Double) = TripOption.through(
            stop.latitude + meters / 111320.0,
            stop.longitude,
            listOf(JourneyFixtures.first, JourneyFixtures.second),
            JourneyFixtures.destination.latitude,
            JourneyFixtures.destination.longitude,
        )
        // 20 m away, 26 m on foot: at the stop already
        val near = Journey.planned(from(20.0), DESTINATION, NOW)
        assertEquals(1, near.index)
        assertTrue(near.phase(NOW) is JourneyPhase.Waiting)
        // 60 m away: the walk is followed
        assertEquals(0, Journey.planned(from(60.0), DESTINATION, NOW).index)
    }

    @Test
    fun aChangeAtTheSameStopWaitsThereForTheNextRide() {
        // Off the T31 at its last stop, Calle 5 con Kr 39, and on from it on the P10
        val p10 = RideLeg("P10", "Universidades", out.subList(6, 10), wait = 2.minutes, duration = 4.minutes)
        val option = TripOption.through(
            JourneyFixtures.origin.latitude,
            JourneyFixtures.origin.longitude,
            listOf(JourneyFixtures.first, p10),
            out[9].latitude,
            out[9].longitude,
        )
        val planned = Journey.planned(option, DESTINATION, NOW)
        assertEquals(4, planned.legs.size)
        val (change, _) = rideToTheChange(planned.took("T31", NOW + 6 * MINUTE))
        assertEquals(2, change.index)
        assertTrue(change.phase(change.legStartedAt) is JourneyPhase.Waiting)
        assertEquals(2, change.pendingRide)
        assertTrue(change.awaits("P10", out[6].stopId))
    }

    @Test
    fun theFinalWalkEndsAtTheDestination() {
        val last = journey.copy(index = 4, legStartedAt = NOW)
        assertEquals(JourneyStep.Goes(last), last.step(NOW + MINUTE))
        val there = last.fixed(fixAt(JourneyFixtures.destination, NOW + 4 * MINUTE))
        assertEquals(JourneyStep.Ends(TripEnding.REACHED), there.step(NOW + 4 * MINUTE))
    }

    @Test
    fun withoutFixesTheFinalWalkEndsWellAfterItsEstimate() {
        val last = journey.copy(index = 4, legStartedAt = NOW)
        val walk = (last.leg as JourneyLeg.Walk).walk.duration.inWholeMilliseconds
        assertEquals(JourneyStep.Goes(last), last.step(NOW + walk + Trip.ESTIMATE_GRACE.inWholeMilliseconds - 1))
        assertEquals(JourneyStep.Ends(TripEnding.REACHED), last.step(NOW + walk + Trip.ESTIMATE_GRACE.inWholeMilliseconds))
    }

    /** The final walk, from [NOW] */
    private val lastWalk = journey.copy(index = 4, legStartedAt = NOW)
    private val lastWalkDue = NOW + (lastWalk.leg as JourneyLeg.Walk).walk.duration.inWholeMilliseconds + Trip.ESTIMATE_GRACE.inWholeMilliseconds

    /** [meters] short of the destination */
    private fun shortOf(meters: Double) = JourneyFixtures.between(JourneyFixtures.destination, JourneyFixtures.origin, meters)

    @Test
    fun aFixWithinItsOwnErrorOfTheDestinationEndsTheFinalWalk() {
        val near = lastWalk.fixed(fixAt(shortOf(70.0), NOW + 3 * MINUTE, accuracy = 25.0))
        assertEquals(JourneyStep.Ends(TripEnding.REACHED), near.step(NOW + 3 * MINUTE))
        // At most 30 m of a fix's error count
        val vague = lastWalk.fixed(fixAt(shortOf(85.0), NOW + 3 * MINUTE, accuracy = 60.0))
        assertTrue(vague.step(NOW + 3 * MINUTE) is JourneyStep.Goes)
    }

    @Test
    fun fixesStoppingShortOfALargeDestinationEndTheFinalWalkByItsEstimate() {
        // As at Unicentro: its point lies inside, 120 m from where the rider stands
        var walk = lastWalk
        for (i in 1..13) walk = walk.fixed(fixAt(shortOf(250.0 - 10.0 * i), NOW + i * 15_000))
        assertTrue(walk.step(lastWalkDue - 1) is JourneyStep.Goes)
        assertEquals(JourneyStep.Ends(TripEnding.REACHED), walk.step(lastWalkDue))
    }

    @Test
    fun theLastFixFarFromTheDestinationKeepsTheFinalWalkPastItsEstimate() {
        val far = lastWalk.fixed(fixAt(shortOf(400.0), NOW + MINUTE))
        assertTrue(far.step(lastWalkDue) is JourneyStep.Goes)
        assertTrue(far.step(lastWalkDue + 30 * MINUTE) is JourneyStep.Goes)
    }

    // Waiting at the stop

    /** At the stop from 5 min */
    private val atStop = journey.fixed(fixAt(stop, NOW + 5 * MINUTE))

    /** [meters] north of the stop, off Calle 5 */
    private fun northOf(meters: Double) = GeoPoint(stop.latitude + meters / 111320.0, stop.longitude)

    /** [from] with a fix every 15 s from [start] until [end], where [pointAt] puts the rider; each step goes on */
    private fun fixes(from: Journey, start: Long, end: Long, pointAt: (Long) -> GeoPoint): Journey {
        var j = from
        var at = start
        while (at < end) {
            j = j.fixed(fixAt(pointAt(at), at))
            val step = j.step(at)
            assertTrue("$at: $step", step is JourneyStep.Goes)
            at += 15_000
        }
        return j
    }

    @Test
    fun aRiderWhoStaysAtTheStopWaitsUntilTheLimit() {
        val since = NOW + 5 * MINUTE
        val limit = since + Journey.WAITING_LIMIT.inWholeMilliseconds
        val waited = fixes(atStop, since, limit) { stop }
        assertEquals(JourneyStep.Ends(TripEnding.NO_BUS), waited.step(limit))
    }

    @Test
    fun withoutFixesTheWaitingByTheEstimateEndsAtTheLimitToo() {
        val since = journey.waitingSince()
        assertTrue(journey.phase(since) is JourneyPhase.Waiting)
        assertTrue(journey.step(since + Journey.WAITING_LIMIT.inWholeMilliseconds - 1) is JourneyStep.Goes)
        assertEquals(JourneyStep.Ends(TripEnding.NO_BUS), journey.step(since + Journey.WAITING_LIMIT.inWholeMilliseconds))
    }

    @Test
    fun aRiderGoneFromTheStopForThreeMinutesEndsTheTrip() {
        val left = NOW + 6 * MINUTE
        val gone = fixes(atStop, left, left + Journey.AWAY_FOR.inWholeMilliseconds) { northOf(500.0) }
        assertEquals(left, gone.awaySince)
        val last = left + Journey.AWAY_FOR.inWholeMilliseconds
        assertEquals(JourneyStep.Ends(TripEnding.LEFT_STOP), gone.fixed(fixAt(northOf(500.0), last)).step(last))
    }

    @Test
    fun aRiderBackAtTheStopIsNoLongerGone() {
        val left = NOW + 6 * MINUTE
        val strayed = fixes(atStop, left, left + 2 * MINUTE) { northOf(500.0) }
        val back = strayed.fixed(fixAt(northOf(100.0), left + 2 * MINUTE))
        assertNull(back.awaySince)
        val again = fixes(back, left + 3 * MINUTE, left + 5 * MINUTE) { northOf(500.0) }
        assertEquals(left + 3 * MINUTE, again.awaySince)
    }

    @Test
    fun aRiderAlongTheRideRouteIsNotGoneAsOnABusNotToldApartYet() {
        val left = NOW + 6 * MINUTE
        // Held 800 m along Calle 5 for ten minutes, as a bus in traffic
        val held = fixes(atStop, left, left + 10 * MINUTE) { calle5Route.pointAt(800.0) }
        assertNull(held.awaySince)
    }

    @Test
    fun threeHoursEndAPlannerTrip() {
        assertEquals(JourneyStep.Ends(TripEnding.TIME_LIMIT), journey.step(NOW + Journey.TIME_LIMIT.inWholeMilliseconds))
    }

    @Test
    fun aSingleRideIsAJourneyOfOneLeg() {
        val single = Journey.single(com.yenreh.caligo.trips.TripFixtures.plan, NOW)
        assertEquals(1, single.legs.size)
        assertNotNull(single.ride)
        assertNull(single.pendingRide)
        assertTrue(!single.planned)
        // The 3 h limit is a planner trip's; a ride has its own 2 h
        assertEquals(JourneyStep.Ends(TripEnding.TIME_LIMIT), single.step(NOW + Trip.TIME_LIMIT.inWholeMilliseconds))
    }

    // What the planner learns

    @Test
    fun aRideBackedByFixesTeachesHowItRan() {
        val (_, step) = rideToTheChange(journey.took("T31", NOW + 6 * MINUTE))
        val readings = (step as JourneyStep.Goes).readings
        // 2000 m from the first stop after boarding to the last, at 7 m/s against the route's 5 x 85.7 s
        val estimate = 5 * (400 * 1.15 / 7 + 20)
        assertEquals(2000 / 7.0 / estimate, readings.getValue("T31"), 0.1)
    }

    @Test
    fun aRideTimedMostlyByTheEstimateTeachesNothing() {
        // On the bus, then nothing until a fix at the last stop
        var j = journey.took("T31", NOW + 6 * MINUTE)
        j = j.fixed(fixAt(calle5Route.pointAt(50.0), NOW + 6 * MINUTE + 10_000))
        j = j.fixed(fixAt(calle5Route.pointAt(2390.0), NOW + 11 * MINUTE))
        j = j.fixed(fixAt(calle5Route.pointAt(2400.0), NOW + 11 * MINUTE + 15_000))
        val step = j.step(NOW + 11 * MINUTE + 15_000) as JourneyStep.Goes
        assertEquals(2, step.journey.index)
        assertTrue(step.readings.toString(), step.readings.isEmpty())
    }

    @Test
    fun theRideReadingIsTheTimeBetweenStopsOverTheRouteAlone() {
        val plan = com.yenreh.caligo.trips.TripFixtures.plan
        var trip = Trip.start(plan, NOW)
        // 8 m/s, a fix every 15 s, to Pampalinda
        var at = NOW
        while (trip.step(at) == TripStep.Goes && at < NOW + 20 * MINUTE) {
            at += 15_000
            trip = trip.fixed(com.yenreh.caligo.trips.TripFixtures.fix((at - NOW) * 8.0 / 1000, at))
        }
        // From the first stop after boarding to Pampalinda: 2400 m at 8 m/s against 6 stretches of 85.7 s
        val reading = checkNotNull(trip.reading())
        assertEquals(2400 / 8.0 / (6 * (400 * 1.15 / 7 + 20)), reading, 0.05)
        assertTrue(trip.passes.all { it.backed })
    }

    @Test
    fun aRideTooShortTeachesNothing() {
        val plan = com.yenreh.caligo.trips.TripFixtures.plan.copy(alight = 2)
        var trip = Trip.start(plan, NOW)
        for (i in 1..12) trip = trip.fixed(com.yenreh.caligo.trips.TripFixtures.fix(i * 75.0, NOW + i * 15_000))
        assertNull(trip.reading())
    }

    @Test
    fun theA47IsTakenByPositionAfterTheChange() {
        val (change, _) = rideToTheChange(journey.took("T31", NOW + 6 * MINUTE))
        val a47 = GeoPoint(JourneyFixtures.a47[0].latitude, JourneyFixtures.a47[0].longitude)
        var j = change.fixed(fixAt(a47, NOW + 12 * MINUTE))
        assertEquals(3, j.index)
        j = j.fixed(fixAt(a47Route.pointAt(120.0), NOW + 14 * MINUTE))
        j = j.fixed(fixAt(a47Route.pointAt(240.0), NOW + 14 * MINUTE + 15_000))
        j = j.withBuses("A47", listOf(busOn(a47Route, "22074", 250.0)), NOW + 14 * MINUTE + 20_000)
        assertEquals("A47", j.ride?.plan?.line)
    }
}
