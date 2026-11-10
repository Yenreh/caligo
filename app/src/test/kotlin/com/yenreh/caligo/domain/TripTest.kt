package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.bus
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripFixtures.plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A trip read at moments: its progress and what backs it, its times, warnings and endings */
class TripTest {
    private val trip = Trip.start(plan, NOW)

    /** Seconds the planner's estimate gives a 400 m stretch of an E line: 400 x 1.15 / 7 + 20 */
    private val stretch = 400 * 1.15 / 7 + 20

    @Test
    fun aTripStartsAtTheBoardingStopOnTheEstimate() {
        val progress = trip.progress(NOW)
        assertEquals(0.0, progress.along, 0.0)
        assertEquals(TripSource.ESTIMATE, progress.source)
        assertEquals(7, progress.stopsLeft)
        assertEquals(7 * stretch * 1000, trip.millisTo(7, NOW).toDouble(), 1000.0)
        assertEquals(10, trip.minutes(trip.millisTo(7, NOW)))
    }

    @Test
    fun theLearnedRideFactorStretchesTheTimes() {
        val slow = Trip.start(plan.copy(factor = 1.5), NOW)
        assertEquals(15, slow.minutes(slow.millisTo(7, NOW)))
    }

    @Test
    fun withoutFixOrBusTheEstimateMovesTheRiderOnAndStopsAtTheStop() {
        assertEquals(400.0, trip.progress(NOW + (stretch * 1000).toLong()).along, 1.0)
        assertEquals(TripSource.ESTIMATE, trip.progress(NOW + 3 * MINUTE).source)
        // Never past where the ride ends
        assertEquals(TripFixtures.line.at[7], trip.progress(NOW + 60 * MINUTE).along, 0.0)
    }

    @Test
    fun aGoodFixPlacesTheRiderUntilItGetsOld() {
        val fixed = trip.fixed(fix(1000.0, NOW + 2 * MINUTE))
        assertEquals(1000.0, fixed.progress(NOW + 2 * MINUTE).along, 1.0)
        assertEquals(TripSource.FIX, fixed.progress(NOW + 2 * MINUTE + 30_000).source)
        // Past its freshness the estimate goes on from there
        val later = fixed.progress(NOW + 2 * MINUTE + 60_000)
        assertEquals(TripSource.ESTIMATE, later.source)
        assertTrue(later.along > 1000.0)
    }

    @Test
    fun aFixOffByBlocksMovesNothing() {
        val network = trip.fixed(fix(1000.0, NOW + MINUTE, accuracy = 800.0))
        assertEquals(trip, network)
    }

    @Test
    fun withoutAFreshFixTheRidersBusStandsIn() {
        val seen = trip.withBuses(listOf(bus("32051", 30.0), bus("other", 2000.0)), NOW + 10_000)
        assertEquals("32051", seen.bus?.busNumber)
        val moved = seen.withBuses(listOf(bus("32051", 600.0)), NOW + 70_000)
        val progress = moved.progress(NOW + 70_000)
        assertEquals(TripSource.BUS, progress.source)
        assertEquals(600.0, progress.along, 1.0)
    }

    @Test
    fun onceMatchedTheBusIsFollowedByItsNumber() {
        val seen = trip.withBuses(listOf(bus("32051", 30.0)), NOW + 10_000)
        // Another bus now nearer where the rider is thought to be does not take its place
        val next = seen.withBuses(listOf(bus("32051", 500.0), bus("12081", 200.0)), NOW + 40_000)
        assertEquals("32051", next.bus?.busNumber)
        assertEquals(500.0, next.bus!!.along, 1.0)
    }

    @Test
    fun aBusLostForThreeAnswersIsMatchedAgain() {
        var t = trip.withBuses(listOf(bus("32051", 30.0)), NOW + 10_000)
        for (i in 1..2) t = t.withBuses(emptyList(), NOW + 10_000 + i * 30_000)
        assertEquals("32051", t.bus?.busNumber)
        assertEquals(2, t.bus?.missed)
        t = t.withBuses(listOf(bus("12081", t.progress(NOW + 100_000).along + 50)), NOW + 100_000)
        assertEquals("12081", t.bus?.busNumber)
    }

    @Test
    fun aBusFarFromTheRidersOwnFixIsNotTheirs() {
        var t = trip.withBuses(listOf(bus("32051", 30.0)), NOW + 10_000)
        t = t.fixed(fix(1200.0, NOW + 2 * MINUTE))
        // The bus matched at the start stayed behind: the rider is on another
        t = t.withBuses(listOf(bus("32051", 300.0), bus("12081", 1150.0)), NOW + 2 * MINUTE + 5_000)
        assertEquals("12081", t.bus?.busNumber)
        // And it never moves the rider while their fix is fresh
        assertEquals(TripSource.FIX, t.progress(NOW + 2 * MINUTE + 5_000).source)
    }

    @Test
    fun theRidersOwnFixOutweighsTheBusStandingIn() {
        var t = trip.withBuses(listOf(bus("32051", 30.0)), NOW + 10_000)
        t = t.withBuses(listOf(bus("32051", 1500.0)), NOW + 3 * MINUTE)
        assertEquals(1500.0, t.progress(NOW + 3 * MINUTE).along, 1.0)
        // A wrong bus ran ahead: the rider's fix puts them back where they are
        t = t.fixed(fix(1100.0, NOW + 3 * MINUTE + 10_000))
        assertEquals(1100.0, t.progress(NOW + 3 * MINUTE + 10_000).along, 1.0)
    }

    @Test
    fun theWarningComesTwoStopsBeforeAndAgainWhenTheStopIsNext() {
        var t = trip
        assertNull(t.warningDue(NOW))
        // Between the fifth and sixth stops: Pampalinda two stops away
        t = t.fixed(fix(2010.0, NOW + 6 * MINUTE))
        assertEquals(Trip.WARN_TWO_STOPS, t.warningDue(NOW + 6 * MINUTE))
        t = t.warnedAt(NOW + 6 * MINUTE)
        assertTrue(t.alerted)
        assertNull(t.warningDue(NOW + 6 * MINUTE))
        t = t.fixed(fix(2410.0, NOW + 7 * MINUTE))
        assertEquals(Trip.WARN_NEXT, t.warningDue(NOW + 7 * MINUTE))
        t = t.warnedAt(NOW + 7 * MINUTE)
        // At the stop, nothing more
        t = t.fixed(fix(2790.0, NOW + 8 * MINUTE))
        assertNull(t.warningDue(NOW + 8 * MINUTE))
    }

    @Test
    fun aJumpPastBothWarningsGivesOne() {
        var t = trip.fixed(fix(1610.0, NOW + 4 * MINUTE)).warnedAt(NOW + 4 * MINUTE)
        assertFalse(t.alerted)
        t = t.fixed(fix(2450.0, NOW + 5 * MINUTE))
        assertEquals(Trip.WARN_NEXT, t.warningDue(NOW + 5 * MINUTE))
        t = t.warnedAt(NOW + 5 * MINUTE)
        assertNull(t.warningDue(NOW + 5 * MINUTE))
    }

    @Test
    fun aStopChosenCloseNeedsNoWarningForWhatIsAlreadyDue() {
        val near = Trip.start(plan.copy(alight = 1), NOW)
        assertNull(near.warningDue(NOW))
        val two = Trip.start(plan.copy(alight = 2), NOW)
        assertNull(two.warningDue(NOW))
        assertEquals(Trip.WARN_NEXT, two.fixed(fix(410.0, NOW + MINUTE)).warningDue(NOW + MINUTE))
    }

    @Test
    fun justFollowingTheRideWarnsOfNothing() {
        val follow = Trip.start(plan.copy(alight = null), NOW).fixed(fix(3210.0, NOW + 9 * MINUTE))
        assertEquals(1, follow.progress(NOW + 9 * MINUTE).stopsLeft)
        assertNull(follow.warningDue(NOW + 9 * MINUTE))
    }

    @Test
    fun anotherStopToGetOffAtStartsTheWarningsOver() {
        var t = trip.fixed(fix(2410.0, NOW + 7 * MINUTE)).warnedAt(NOW + 7 * MINUTE)
        t = t.alightAt(9, NOW + 7 * MINUTE)
        assertEquals(3, t.progress(NOW + 7 * MINUTE).stopsLeft)
        t = t.fixed(fix(2810.0, NOW + 8 * MINUTE))
        assertEquals(Trip.WARN_TWO_STOPS, t.warningDue(NOW + 8 * MINUTE))
    }

    // Endings

    @Test
    fun passingTheStopEndsTheTrip() {
        val t = trip.fixed(fix(2800.0 + Trip.PASSED_METERS + 10, NOW + 9 * MINUTE))
        assertEquals(TripStep.Ends(TripEnding.REACHED), t.step(NOW + 9 * MINUTE))
    }

    @Test
    fun standingAtTheStopAWhileEndsTheTrip() {
        val t = trip.fixed(fix(2790.0, NOW + 9 * MINUTE))
        assertEquals(TripStep.Goes, t.step(NOW + 9 * MINUTE + Trip.AT_STOP_FOR.inWholeMilliseconds - 1))
        assertEquals(TripStep.Ends(TripEnding.REACHED), t.step(NOW + 9 * MINUTE + Trip.AT_STOP_FOR.inWholeMilliseconds))
    }

    @Test
    fun theEstimateAloneEndsTheTripWellAfterItReachedTheStop() {
        val reached = NOW + (7 * stretch * 1000).toLong()
        assertEquals(TripStep.Goes, trip.step(reached + Trip.ESTIMATE_GRACE.inWholeMilliseconds - 1_000))
        assertEquals(TripStep.Ends(TripEnding.REACHED), trip.step(reached + Trip.ESTIMATE_GRACE.inWholeMilliseconds + 1_000))
    }

    @Test
    fun goodFixesOffTheRouteForMinutesEndTheTrip() {
        var t = trip.fixed(fix(400.0, NOW + MINUTE))
        t = t.fixed(fix(500.0, NOW + 2 * MINUTE, north = 500.0))
        assertEquals(NOW + 2 * MINUTE, t.offRouteSince)
        // Back on the route: not off any more
        assertNull(t.fixed(fix(520.0, NOW + 3 * MINUTE)).offRouteSince)
        t = t.fixed(fix(500.0, NOW + 4 * MINUTE, north = 500.0))
        assertEquals(TripStep.Goes, t.step(NOW + 2 * MINUTE + Trip.OFF_ROUTE_FOR.inWholeMilliseconds - 1))
        assertEquals(TripStep.Ends(TripEnding.OFF_ROUTE), t.step(NOW + 2 * MINUTE + Trip.OFF_ROUTE_FOR.inWholeMilliseconds))
    }

    @Test
    fun aFixJustBeyondTheCorridorWithinItsErrorIsNotOff() {
        val t = trip.fixed(fix(400.0, NOW + MINUTE)).fixed(fix(420.0, NOW + 2 * MINUTE, north = 200.0, accuracy = 80.0))
        assertNull(t.offRouteSince)
    }

    @Test
    fun twoHoursEndTheTrip() {
        val held = trip.fixed(fix(10.0, NOW + Trip.TIME_LIMIT.inWholeMilliseconds - 10_000))
        assertEquals(TripStep.Goes, held.step(NOW + Trip.TIME_LIMIT.inWholeMilliseconds - 1))
        assertEquals(TripStep.Ends(TripEnding.TIME_LIMIT), held.step(NOW + Trip.TIME_LIMIT.inWholeMilliseconds))
    }

    // Reaching the stop with the bus as the only sign of it

    @Test
    fun aBusAtTheStopStartsNoCountAndAFixShortOfItClearsAny() {
        var t = trip.withBuses(listOf(bus("32051", 30.0)), NOW + 10_000)
        // A bus run ahead has the rider at Pampalinda
        t = t.withBuses(listOf(bus("32051", 2790.0)), NOW + 5 * MINUTE)
        assertEquals(2790.0, t.confirmed, 1.0)
        assertNull(t.atStopSince)
        // The rider's own fix: a stop short
        t = t.fixed(fix(2400.0, NOW + 5 * MINUTE + 10_000))
        assertEquals(2400.0, t.confirmed, 1.0)
        assertNull(t.atStopSince)
        assertEquals(TripStep.Goes, t.step(NOW + 5 * MINUTE + Trip.AT_STOP_FOR.inWholeMilliseconds + 20_000))
    }

    @Test
    fun aCountFromAFixAtTheStopGoesWhenAnotherStopIsChosen() {
        var t = trip.fixed(fix(2790.0, NOW + 9 * MINUTE))
        assertEquals(NOW + 9 * MINUTE, t.atStopSince)
        t = t.alightAt(9, NOW + 9 * MINUTE)
        assertNull(t.atStopSince)
        assertEquals(TripStep.Goes, t.step(NOW + 12 * MINUTE))
    }

    /**
     * The line's buses every 30 s for [millis], each at [along] of the
     * time, or left out where it gives null; [fixes] at their times. The
     * trip as it stands, and when it ended as reached, if it did.
     */
    private fun ride(millis: Long, fixes: Map<Long, Double> = emptyMap(), along: (number: String, t: Long) -> Double?): Pair<Trip, Long?> {
        var t = trip
        var at = 10_000L
        while (at <= millis) {
            fixes[at]?.let { t = t.fixed(fix(it, NOW + at)) }
            val buses = listOf("mine", "wrong").mapNotNull { n -> along(n, at)?.let { bus(n, it) } }
            t = t.withBuses(buses, NOW + at)
            if (t.step(NOW + at) == TripStep.Ends(TripEnding.REACHED)) return t to at
            at += 30_000
        }
        return t to null
    }

    @Test
    fun theRightBusAloneEndsTheRideOnTime() {
        // 5 m/s: past Pampalinda, 2860 m, at 572 s
        val (t, reached) = ride(15 * MINUTE) { n, at -> if (n == "mine") at * 5.0 / 1000 else null }
        assertEquals("mine", t.bus?.busNumber)
        // Seen past it in two answers in a row
        assertTrue(reached.toString(), reached != null && reached in 572_000L..632_000L)
    }

    @Test
    fun aBusPastTheStopBeforeHalfTheRideEndsNothing() {
        // 20 m/s, past Pampalinda within 2.5 minutes of a ride estimated at 10
        val (_, reached) = ride(4 * MINUTE + 50_000) { n, at -> if (n == "mine") at * 20.0 / 1000 else null }
        assertNull(reached)
        val (_, later) = ride(15 * MINUTE) { n, at -> if (n == "mine") at * 20.0 / 1000 else null }
        assertEquals(5 * MINUTE + 10_000L, later)
    }

    @Test
    fun aWrongBusAheadIsDroppedByTheRidersFixAndWhatItGaveUndone() {
        // Matched at the start: the rider's own bus not reporting yet, another 350 m ahead at 7 m/s
        val wrong = { at: Long -> 350 + at * 7.0 / 1000 }
        val mine = { at: Long -> at * 5.0 / 1000 }
        val (early, _) = ride(3 * MINUTE + 40_000) { n, at -> if (n == "wrong") wrong(at) else null }
        assertEquals("wrong", early.bus?.busNumber)
        assertEquals(TripSource.BUS, early.confirmedBy)
        // At 4 minutes a fix of the rider's own, 1200 m in, against the wrong bus at 2030 m
        val (t, reached) = ride(15 * MINUTE, fixes = mapOf(250_000L to mine(250_000))) { n, at ->
            if (n == "wrong") wrong(at) else if (at >= 4 * MINUTE) mine(at) else null
        }
        // The wrong bus passed Pampalinda at 358 s and ended nothing: the rider's bus did, at 572 s
        assertEquals("mine", t.bus?.busNumber)
        assertTrue(reached.toString(), reached != null && reached in 572_000L..632_000L)
        // Right after the fix the rider was where it put them, the wrong bus's progress undone
        val (atFix, _) = ride(250_000, fixes = mapOf(250_000L to mine(250_000))) { n, at ->
            if (n == "wrong") wrong(at) else if (at >= 4 * MINUTE) mine(at) else null
        }
        assertEquals(1250.0, atFix.confirmed, 30.0)
        assertEquals("mine", atFix.bus?.busNumber)
    }

    @Test
    fun aBusDroppedWhileItAloneHadMovedTheRiderTakesItsProgressBack() {
        // The bus stood in last, after the rider's fix: then the fix, fresh, shows it far ahead
        var t = trip.fixed(fix(400.0, NOW + MINUTE))
        t = t.withBuses(listOf(bus("mine", 420.0)), NOW + MINUTE + 1_000)
        assertEquals(TripSource.FIX, t.confirmedBy)
        // A fix stale by the next answers, and the bus far ahead
        t = t.withBuses(listOf(bus("mine", 1300.0)), NOW + 3 * MINUTE)
        assertEquals(TripSource.BUS, t.confirmedBy)
        assertEquals(1300.0, t.confirmed, 1.0)
        // Now a fresh fix a long way behind: the rider was never on it, and what it gave goes
        t = t.fixed(fix(500.0, NOW + 3 * MINUTE + 10_000))
        assertEquals(500.0, t.confirmed, 1.0)
        t = t.withBuses(listOf(bus("mine", 1400.0)), NOW + 3 * MINUTE + 20_000)
        assertNull(t.bus)
        assertEquals(500.0, t.confirmed, 1.0)
    }

    // The ride's direction

    private fun stop(id: String, name: String, direction: Int, sequence: Int) = LineStop(id, name, 3.45, -76.54 + sequence * 0.003, direction, sequence)

    @Test
    fun theDirectionIsTheOneCallingAtTheBoardingStop() {
        assertEquals(0, plan.direction)
        assertEquals(0, plan.board)
        assertEquals(1..9, plan.ahead)
    }

    @Test
    fun aStopServedBothWaysTakesTheDirectionNamedAsTheBusDestination() {
        val route = listOf(
            stop("1", "Universidades", 0, 1), stop("x", "Centro", 0, 2), stop("2", "Est. Menga", 0, 3),
            stop("2", "Est. Menga", 1, 1), stop("x", "Centro", 1, 2), stop("1", "Universidades", 1, 3),
        )
        val took = TripFixtures.took.copy(stopId = "x")
        assertEquals(1, TripPlan.of(took.copy(destination = "Universidades"), route)?.direction)
        assertEquals(0, TripPlan.of(took.copy(destination = "Menga"), route)?.direction)
    }

    @Test
    fun failingTheNameTheDirectionWithMoreStopsAheadWins() {
        val route = listOf(
            stop("a", "A", 0, 1), stop("x", "X", 0, 2), stop("b", "B", 0, 3),
            stop("c", "C", 1, 1), stop("d", "D", 1, 2), stop("e", "E", 1, 3), stop("x", "X", 1, 4), stop("f", "F", 1, 5), stop("g", "G", 1, 6),
        )
        assertEquals(1, TripPlan.of(TripFixtures.took.copy(stopId = "x", destination = "Somewhere"), route)?.direction)
    }

    @Test
    fun aStopTheLineDoesNotCallAtOrEndsAtHasNoRide() {
        assertNull(TripPlan.of(TripFixtures.took.copy(stopId = "nowhere"), TripFixtures.route))
        // The last stop going out, which coming back is a stop of its own
        assertNull(TripPlan.of(TripFixtures.took.copy(stopId = TripFixtures.out.last().stopId), TripFixtures.out))
    }

    @Test
    fun aStationsNameMatchesTheDestinationWhateverItsPlatform() {
        assertTrue(TripPlan.sameName("Universidades B1", "Est. Universidades"))
        assertFalse(TripPlan.sameName("Menga", "Universidades"))
    }
}
