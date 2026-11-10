package com.yenreh.caligo.trips

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripFixtures.plan
import com.yenreh.caligo.waits.Alerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A trip's notification as a pure function of the trip and the time, in English and Spanish */
@RunWith(AndroidJUnit4::class)
class TripNoticeTest {
    private val words = TripWords(ApplicationProvider.getApplicationContext<Context>().resources)
    private val trip = Trip.start(plan, NOW)

    private fun notice(trip: Trip, now: Long, alerts: Alerts = Alerts(), warningOn: Boolean = true) =
        TripNotice.of(trip, now, alerts, words, 15_000, warningOn)

    @Test
    fun onTheEstimateTheTimeReadsAbout() {
        val n = notice(trip, NOW)
        assertEquals("E21 to Pampalinda, ~10 min", n.title)
        assertEquals("7 stops · next: Centro", n.text)
        assertEquals("~10 min", n.chip)
        assertEquals(NOW + trip.millisTo(7, NOW), n.countdownTo)
        assertEquals(TripChannel.TRIPS, n.channel)
        assertFalse(n.alerting)
    }

    @Test
    fun aFixBacksTheTime() {
        val fixed = trip.fixed(fix(1210.0, NOW + 3 * MINUTE))
        val n = notice(fixed, NOW + 3 * MINUTE)
        assertEquals("E21 to Pampalinda, 6 min", n.title)
        assertEquals("4 stops · next: Calle 5 con Kr 22", n.text)
        assertEquals("6 min", n.chip)
        // The bar from the boarding stop to Pampalinda, in metres, with the next stops on it
        assertEquals(2800.0, n.progressMax.toDouble(), 3.0)
        assertEquals(1210.0, n.progress.toDouble(), 2.0)
        assertEquals(listOf(1600, 2000, 2400), n.points.map { it.position }.map { (it + 50) / 100 * 100 })
    }

    @Test
    fun theBarShowsTheNextFourStopsAtMost() {
        assertEquals(TripNotice.MAX_POINTS, notice(trip, NOW).points.size)
        assertEquals(400, (notice(trip, NOW).points.first().position + 5) / 10 * 10)
    }

    @Test
    fun twoStopsAwayTheWarningGoesOutOnTheChannelTheSettingsAsk() {
        val near = trip.fixed(fix(2010.0, NOW + 6 * MINUTE))
        val n = notice(near, NOW + 6 * MINUTE, Alerts(sound = true))
        assertTrue(n.alerting)
        assertEquals(TripChannel.WARNING_SOUND, n.channel)
        assertEquals("E21 to Pampalinda, 3 min", n.title)
        assertEquals("2 stops · next: Calle 5 con Kr 39", n.text)
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
        assertEquals("Pampalinda · in 1 min", notice(next, NOW + 7 * MINUTE).text)
        assertEquals("Next", notice(next, NOW + 7 * MINUTE).chip)
        val here = next.fixed(fix(2790.0, NOW + 8 * MINUTE))
        val n = notice(here, NOW + 8 * MINUTE)
        assertEquals("E21, get off here", n.title)
        assertEquals("Pampalinda", n.text)
        assertEquals("Here", n.chip)
        assertNull(n.countdownTo)
        assertTrue(n.points.isEmpty())
    }

    @Test
    fun justFollowingTheRideSaysTheNextStopAndTheEnd() {
        val follow = Trip.start(plan.copy(alight = null), NOW)
        val n = notice(follow, NOW)
        assertEquals("E21 to Universidades, ~13 min", n.title)
        assertEquals("9 stops · next: Centro", n.text)
    }

    @Test
    fun offTheRouteItSaysSo() {
        val off = trip.fixed(fix(400.0, NOW + MINUTE)).fixed(fix(450.0, NOW + 2 * MINUTE, north = 500.0))
        assertEquals("Away from the route", notice(off, NOW + 2 * MINUTE).text)
    }

    @Test
    fun itGoesByItselfSoonAfterItsNextUpdateIsDue() {
        assertEquals(15_000 + TripNotice.TIMEOUT_MARGIN_MS, notice(trip, NOW).timeoutAfter)
    }

    @Test
    @Config(qualifiers = "es")
    fun inSpanish() {
        val es = TripWords(ApplicationProvider.getApplicationContext<Context>().resources)
        assertEquals("E21 a Pampalinda, ~10 min", TripNotice.of(trip, NOW, Alerts(), es, 15_000).title)
        assertEquals("7 paradas · próxima: Centro", TripNotice.of(trip, NOW, Alerts(), es, 15_000).text)
        val next = trip.fixed(fix(2410.0, NOW + 7 * MINUTE))
        assertEquals("E21, bájate en la próxima", TripNotice.of(next, NOW + 7 * MINUTE, Alerts(), es, 15_000).title)
        assertEquals("Pampalinda · en 1 min", TripNotice.of(next, NOW + 7 * MINUTE, Alerts(), es, 15_000).text)
    }
}
