package com.yenreh.caligo.trips

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.NotificationCompat
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.between
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.fixAt
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.JourneyFixtures.origin
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripFixtures.out
import com.yenreh.caligo.trips.TripFixtures.plan
import com.yenreh.caligo.waits.Alerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A trip's notification as a pure function of the trip and the time, in English and Spanish */
@RunWith(AndroidJUnit4::class)
class TripNoticeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val words = TripWords(context.resources)
    private val trip = Trip.start(plan, NOW)

    private fun notice(trip: Trip, now: Long, alerts: Alerts = Alerts(), warningOn: Boolean = true) =
        TripNotice.of(trip, now, alerts, words, 15_000, warningOn)

    @Test
    fun onTheEstimateTheTimeReadsAbout() {
        val n = notice(trip, NOW)
        assertEquals("Get off at Pampalinda, ~10${NBSP}min", n.title)
        assertEquals("E21 · 7 stops · next: Centro", n.text)
        assertEquals("~10${NBSP}min", n.chip)
        assertEquals(TripChannel.TRIPS, n.channel)
        assertFalse(n.alerting)
    }

    @Test
    fun aFixBacksTheTime() {
        val fixed = trip.fixed(fix(1210.0, NOW + 3 * MINUTE))
        val n = notice(fixed, NOW + 3 * MINUTE)
        assertEquals("Get off at Pampalinda, 6${NBSP}min", n.title)
        assertEquals("E21 · 4 stops · next: Calle 5 con Kr 22", n.text)
        assertEquals("6${NBSP}min", n.chip)
    }

    // The ride's bar: one segment in metres, with the last four stops before getting off

    private fun TripNotice.rounded() = points.map { (it.position + 50) / 100 * 100 }

    @Test
    fun theRideBarRunsInMetresToWhereTheRideEnds() {
        val n = notice(trip.fixed(fix(1210.0, NOW + 3 * MINUTE)), NOW + 3 * MINUTE)
        assertEquals(2800.0, n.progressMax.toDouble(), 3.0)
        assertEquals(1210.0, n.progress.toDouble(), 2.0)
        assertEquals(TripTracker.BUS, n.tracker)
    }

    @Test
    fun theBarMarksTheLastFourStopsBeforeGettingOffForTheWholeRide() {
        val stops = listOf(1200, 1600, 2000, 2400)
        assertEquals(stops, notice(trip, NOW).rounded())
        // Passed ones stay, where they were
        val past = trip.fixed(fix(1210.0, NOW + 3 * MINUTE))
        assertEquals(stops, notice(past, NOW + 3 * MINUTE).rounded())
        val next = past.fixed(fix(2410.0, NOW + 7 * MINUTE))
        assertEquals(stops, notice(next, NOW + 7 * MINUTE).rounded())
        assertEquals(stops, notice(next.fixed(fix(2790.0, NOW + 8 * MINUTE)), NOW + 8 * MINUTE).rounded())
        // Following the line, toward its last stop
        assertEquals(listOf(2000, 2400, 2800, 3200), notice(Trip.start(plan.copy(alight = null), NOW), NOW).rounded())
    }

    /** Stops [step] metres apart from the boarding one, [count] spans in all */
    private fun stopsAt(count: Int, step: Double = 400.0) = DoubleArray(count + 1) { it * step }

    private fun lastStops(at: DoubleArray, board: Int, target: Int) =
        TripNotice.lastStops(at, board, target, (at[target] - at[board]).toInt()).map { it.position }

    @Test
    fun aRideOfAStopOrTwoMarksWhatLiesBetween() {
        assertEquals(emptyList<Int>(), lastStops(stopsAt(1), 0, 1))
        assertEquals(listOf(400), lastStops(stopsAt(2), 0, 2))
    }

    @Test
    fun aRideOfFiveStopsMarksEveryStopBetween() {
        assertEquals(listOf(400, 800, 1200, 1600), lastStops(stopsAt(5), 0, 5))
        // Boarding further along the line, from its own start
        assertEquals(listOf(400, 800, 1200, 1600), lastStops(stopsAt(12), 7, 12))
    }

    @Test
    fun aLongRideMarksItsLastFourStops() {
        assertEquals(listOf(5200, 5600, 6000, 6400), lastStops(stopsAt(17), 0, 17))
        for (spans in 1..30) {
            val at = stopsAt(spans + 3)
            val points = lastStops(at, 2, spans + 2)
            val length = at[spans + 2] - at[2]
            assertTrue(points.size <= TripNotice.MAX_POINTS)
            assertTrue(points.all { it > 0 && it < length })
            assertEquals(points.sorted().distinct(), points)
        }
    }

    @Test
    fun twoBerthsOfAStopAreOnePoint() {
        val at = doubleArrayOf(0.0, 400.0, 400.0, 800.0, 1200.0)
        assertEquals(listOf(400, 800), lastStops(at, 0, 4))
        // Nor a point at either end, which Android drops
        assertEquals(listOf(400), lastStops(doubleArrayOf(0.0, 0.0, 400.0, 800.0, 800.0), 0, 4))
    }

    @Test
    fun twoStopsAwayTheWarningGoesOutOnTheChannelTheSettingsAsk() {
        val near = trip.fixed(fix(2010.0, NOW + 6 * MINUTE))
        val n = notice(near, NOW + 6 * MINUTE, Alerts(sound = true))
        assertTrue(n.alerting)
        assertEquals(TripChannel.WARNING_SOUND, n.channel)
        assertEquals("Get off at Pampalinda, 3${NBSP}min", n.title)
        assertEquals("E21 · 2 stops · next: Calle 5 con Kr 39", n.text)
        // Once out, it stays on that channel, silent
        val after = notice(near.warnedAt(NOW + 6 * MINUTE), NOW + 6 * MINUTE + 15_000, Alerts(sound = true))
        assertFalse(after.alerting)
        assertEquals(TripChannel.WARNING_SOUND, after.channel)
    }

    @Test
    fun withTheWarningChannelOffThereIsNoWarningAndEveryPostStaysOnTheTripsChannel() {
        val near = trip.fixed(fix(2010.0, NOW + 6 * MINUTE))
        val n = notice(near, NOW + 6 * MINUTE, warningOn = false)
        assertFalse(n.alerting)
        assertEquals(TripChannel.TRIPS, n.channel)
    }

    @Test
    fun theStopNextAndTheStopReached() {
        val next = trip.fixed(fix(2410.0, NOW + 7 * MINUTE))
        assertEquals("E21, get off at the next stop", notice(next, NOW + 7 * MINUTE).title)
        assertEquals("Pampalinda · in 1${NBSP}min", notice(next, NOW + 7 * MINUTE).text)
        assertEquals("Next", notice(next, NOW + 7 * MINUTE).chip)
        val here = next.fixed(fix(2790.0, NOW + 8 * MINUTE))
        val n = notice(here, NOW + 8 * MINUTE)
        assertEquals("E21, get off here", n.title)
        assertEquals("Pampalinda", n.text)
        assertEquals("Here", n.chip)
    }

    @Test
    fun justFollowingTheRideSaysTheNextStopAndTheEnd() {
        val follow = Trip.start(plan.copy(alight = null), NOW)
        val n = notice(follow, NOW)
        // There the bus's own destination, as the waits say it
        assertEquals("E21 to Universidades, ~13${NBSP}min", n.title)
        assertEquals("9 stops · next: Centro", n.text)
        val end = follow.fixed(fix(3200.0, NOW + 9 * MINUTE)).fixed(fix(3590.0, NOW + 10 * MINUTE))
        assertEquals("E21, end of the line", notice(end, NOW + 10 * MINUTE).title)
    }

    /** [trip] at [along] metres, then a fix a few blocks north of it */
    private fun offAt(trip: Trip, along: Double, at: Long) = trip.fixed(fix(along, at - MINUTE)).fixed(fix(along + 40, at, north = 500.0))

    @Test
    fun offTheRouteTheTextNamesTheLineWhereTheTitleDoesNot() {
        val off = notice(offAt(trip, 400.0, NOW + 2 * MINUTE), NOW + 2 * MINUTE)
        assertEquals("Get off at Pampalinda, ~8${NBSP}min", off.title)
        assertEquals("E21 · Away from the route", off.text)
        // The titles that name it already
        val follow = notice(offAt(Trip.start(plan.copy(alight = null), NOW), 400.0, NOW + 2 * MINUTE), NOW + 2 * MINUTE)
        assertEquals("E21 to Universidades, ~10${NBSP}min", follow.title)
        assertEquals("Away from the route", follow.text)
        val next = notice(offAt(trip, 2410.0, NOW + 8 * MINUTE), NOW + 8 * MINUTE)
        assertEquals("E21, get off at the next stop", next.title)
        assertEquals("Away from the route", next.text)
        val here = notice(offAt(trip, 2790.0, NOW + 9 * MINUTE), NOW + 9 * MINUTE)
        assertEquals("E21, get off here", here.title)
        assertEquals("Away from the route", here.text)
    }

    // The planner trip's phases: no clock in any, the walker on foot and the bus otherwise

    private val stop = GeoPoint(out[0].latitude, out[0].longitude)
    private val planned = Journey.planned(option, DESTINATION, NOW - 3 * MINUTE)
    private val walking = planned.fixed(fixAt(between(origin, stop, 200.0), NOW))
    private val waiting = planned.fixed(fixAt(stop, NOW - MINUTE))
    private val riding = planned.took("T31", NOW - 2 * MINUTE).fixed(fixAt(calle5Route.pointAt(900.0), NOW))
    private val buses = listOf(AwaitedLine("E21", NOW + 2 * MINUTE), AwaitedLine("T31", NOW + 5 * MINUTE))

    private fun phaseNotice(journey: Journey) =
        TripNotice.of(journey, NOW, Alerts(), words, 15_000, awaited = if (journey.ride == null) buses else emptyList(), near = true)

    @Test
    fun theTrackerIsTheWalkerOnFootAndTheBusOtherwise() {
        assertTrue(walking.phase(NOW) is JourneyPhase.Walking)
        assertTrue(waiting.phase(NOW) is JourneyPhase.Waiting)
        assertTrue(riding.phase(NOW) is JourneyPhase.Riding)
        assertEquals(TripTracker.WALKER, phaseNotice(walking).tracker)
        assertEquals(TripTracker.BUS, phaseNotice(waiting).tracker)
        assertEquals(TripTracker.BUS, phaseNotice(riding).tracker)
    }

    @Test
    fun noPhaseShowsAClockButTheChipKeepsTheMinutes() {
        for (journey in listOf(walking, waiting, riding)) {
            val notice = phaseNotice(journey)
            val shown = TripNotifications.build(context, journey.startedAt, notice, Alerts(), words)
            assertFalse(NotificationCompat.getUsesChronometer(shown))
            assertFalse(NotificationCompat.getShowWhen(shown))
            assertEquals(notice.chip, NotificationCompat.getShortCriticalText(shown))
            assertTrue(notice.chip.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun itGoesByItselfSoonAfterItsNextUpdateIsDue() {
        assertEquals(15_000 + TripNotice.TIMEOUT_MARGIN_MS, notice(trip, NOW).timeoutAfter)
    }

    @Test
    @Config(qualifiers = "es")
    fun inSpanish() {
        val es = TripWords(ApplicationProvider.getApplicationContext<Context>().resources)
        assertEquals("Bájate en Pampalinda, ~10${NBSP}min", TripNotice.of(trip, NOW, Alerts(), es, 15_000).title)
        assertEquals("E21 · 7 paradas · próxima: Centro", TripNotice.of(trip, NOW, Alerts(), es, 15_000).text)
        val last = trip.fixed(fix(2010.0, NOW + 6 * MINUTE))
        assertEquals("E21 · 2 paradas · próxima: Calle 5 con Kr 39", TripNotice.of(last, NOW + 6 * MINUTE, Alerts(), es, 15_000).text)
        val next = trip.fixed(fix(2410.0, NOW + 7 * MINUTE))
        assertEquals("E21, bájate en la próxima", TripNotice.of(next, NOW + 7 * MINUTE, Alerts(), es, 15_000).title)
        assertEquals("Pampalinda · en 1${NBSP}min", TripNotice.of(next, NOW + 7 * MINUTE, Alerts(), es, 15_000).text)
        val here = next.fixed(fix(2790.0, NOW + 8 * MINUTE))
        assertEquals("E21, bájate aquí", TripNotice.of(here, NOW + 8 * MINUTE, Alerts(), es, 15_000).title)
        // Following the line to its end, and off the route
        val follow = Trip.start(plan.copy(alight = null), NOW)
        assertEquals("E21 a Universidades, ~13${NBSP}min", TripNotice.of(follow, NOW, Alerts(), es, 15_000).title)
        assertEquals("9 paradas · próxima: Centro", TripNotice.of(follow, NOW, Alerts(), es, 15_000).text)
        val off = offAt(trip, 400.0, NOW + 2 * MINUTE)
        assertEquals("E21 · Fuera de la ruta", TripNotice.of(off, NOW + 2 * MINUTE, Alerts(), es, 15_000).text)
        val offFollowing = offAt(follow, 400.0, NOW + 2 * MINUTE)
        assertEquals("Fuera de la ruta", TripNotice.of(offFollowing, NOW + 2 * MINUTE, Alerts(), es, 15_000).text)
        val offNext = offAt(trip, 2410.0, NOW + 8 * MINUTE)
        assertEquals("E21, bájate en la próxima", TripNotice.of(offNext, NOW + 8 * MINUTE, Alerts(), es, 15_000).title)
        assertEquals("Fuera de la ruta", TripNotice.of(offNext, NOW + 8 * MINUTE, Alerts(), es, 15_000).text)
    }

    @Test
    @Config(qualifiers = "es")
    fun inSpanishOneStopLeftFollowingTheLine() {
        val es = TripWords(ApplicationProvider.getApplicationContext<Context>().resources)
        val follow = Trip.start(plan.copy(alight = null), NOW).fixed(fix(3200.0, NOW + 9 * MINUTE))
        assertEquals("1 parada · próxima: Universidades", TripNotice.of(follow, NOW + 9 * MINUTE, Alerts(), es, 15_000).text)
    }

    @Test
    fun inEnglishOneStopLeftFollowingTheLine() {
        val follow = Trip.start(plan.copy(alight = null), NOW).fixed(fix(3200.0, NOW + 9 * MINUTE))
        assertEquals("1 stop · next: Universidades", notice(follow, NOW + 9 * MINUTE).text)
    }

    // The last word of a trip that ended by itself

    private fun ended(journey: Journey, ending: com.yenreh.caligo.domain.TripEnding, warningOn: Boolean = true) =
        EndedNotice.of(journey, ending, Alerts(vibrate = true), words, warningOn)

    @Test
    fun onlyEndingsTheRiderDidNotAskForSayAWord() {
        val single = Journey.single(plan, NOW)
        for (ending in com.yenreh.caligo.domain.TripEnding.entries) {
            assertEquals(ending.name, ending in EndedNotice.NOTICED, ended(single, ending) != null)
        }
        assertEquals(
            setOf("OFF_ROUTE", "NO_LOCATION", "TIME_LIMIT", "LEFT_STOP", "NO_BUS"),
            EndedNotice.NOTICED.map { it.name }.toSet(),
        )
    }

    @Test
    fun withAStopToGetOffAtAheadItPeeksAsTheAlertsAreSet() {
        val n = checkNotNull(ended(Journey.single(plan, NOW), com.yenreh.caligo.domain.TripEnding.OFF_ROUTE))
        assertEquals("Trip ended: watch for your stop", n.title)
        assertEquals("You left the route, so the trip ended.", n.text)
        assertEquals(TripChannel.WARNING_VIBRATE, n.channel)
        assertTrue(n.alerting)
        // A planner trip with a ride ahead, on foot to its stop
        assertEquals(TripChannel.WARNING_VIBRATE, ended(planned, com.yenreh.caligo.domain.TripEnding.TIME_LIMIT)?.channel)
    }

    @Test
    fun withNoStopAheadItIsSilent() {
        // Following the line, no stop chosen
        val following = checkNotNull(ended(Journey.single(plan.copy(alight = null), NOW), com.yenreh.caligo.domain.TripEnding.TIME_LIMIT))
        assertEquals("Trip ended", following.title)
        assertEquals("The trip ended: it ran too long.", following.text)
        assertEquals(TripChannel.TRIPS, following.channel)
        assertFalse(following.alerting)
        // On the final walk
        val walking = planned.copy(index = 4, legStartedAt = NOW)
        assertEquals(TripChannel.TRIPS, ended(walking, com.yenreh.caligo.domain.TripEnding.TIME_LIMIT)?.channel)
        // Gone from the stop: on no bus of the trip
        val gone = checkNotNull(ended(planned.copy(index = 1), com.yenreh.caligo.domain.TripEnding.LEFT_STOP))
        assertEquals("Trip ended", gone.title)
        assertEquals("The trip ended: you left the stop.", gone.text)
        assertEquals(TripChannel.TRIPS, gone.channel)
    }

    @Test
    fun withTheWarningChannelOffItIsSilentYetSaysTheStop() {
        val n = checkNotNull(ended(Journey.single(plan, NOW), com.yenreh.caligo.domain.TripEnding.NO_LOCATION, warningOn = false))
        assertEquals("Trip ended: watch for your stop", n.title)
        assertEquals(TripChannel.TRIPS, n.channel)
        assertFalse(n.alerting)
    }

    @Test
    @Config(qualifiers = "es")
    fun theLastWordInSpanish() {
        val esWords = TripWords(context.resources)
        val watch = checkNotNull(EndedNotice.of(planned.copy(index = 1), com.yenreh.caligo.domain.TripEnding.NO_BUS, Alerts(), esWords))
        assertEquals("Viaje terminado: pendiente de tu parada", watch.title)
        assertEquals("El viaje terminó: no tomaste ningún bus en 45${NBSP}min.", watch.text)
        val gone = checkNotNull(EndedNotice.of(planned.copy(index = 1), com.yenreh.caligo.domain.TripEnding.LEFT_STOP, Alerts(), esWords))
        assertEquals("Viaje terminado", gone.title)
        assertEquals("El viaje terminó: te alejaste de la parada.", gone.text)
        assertEquals("El viaje terminó: duró demasiado.", esWords.endedReason(com.yenreh.caligo.domain.TripEnding.TIME_LIMIT))
    }
}
