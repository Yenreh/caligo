package com.yenreh.caligo.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripSource
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.bus
import com.yenreh.caligo.trips.TripFixtures.plan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A scripted ride on virtual time: the E21 from Plaza de Cayzedo to
 * Pampalinda at 5 m/s, with fixes and the line's buses as the script
 * gives them, a stretch the bus stands in for the fixes and one with
 * neither. It must give the right progress, times, warnings and end.
 */
@RunWith(AndroidJUnit4::class)
class TripRideTest {
    private val h = TripHarness()

    @After
    fun tearDown() = h.tearDown()

    /** Where the bus really is */
    private fun along(at: Long) = (at - NOW) * 5.0 / 1000

    /** One answer of the line's buses: the rider's, one ahead, one coming the other way */
    private fun buses(at: Long) = listOf(
        bus("12081", along(at) + 1500),
        bus("32051", along(at)),
        bus("40017", along(at) + 50, direction = 1, north = 110.0),
    )

    private data class Moment(val minute: Double, val source: TripSource?, val title: String, val stopsLeft: Int, val warned: Int, val channel: String?)

    @Test
    fun aRideFromBoardingToTheStopToGetOffAt() {
        val noFixes = NOW + 2 * MINUTE until NOW + 6 * MINUTE
        val noBus = NOW + 4 * MINUTE until NOW + 6 * MINUTE
        h.source.answer = { at -> if (at in noBus) throw NetworkApiException("offline") else buses(at) }
        h.start(plan)
        val moments = mutableListOf<Moment>()
        while (h.desk.running.value != null && h.now() < NOW + 15 * MINUTE) {
            h.ride(TripHarness.FIX_EVERY) { at -> if (at in noFixes) null else along(at) + listOf(-20.0, 15.0, 0.0)[((at / 15_000) % 3).toInt()] }
            val trip = h.desk.running.value?.ride ?: break
            val at = h.now()
            val progress = trip.progress(at)
            moments.add(
                Moment(
                    (at - NOW) / 60_000.0,
                    progress.source,
                    with(h) { checkNotNull(shown).title() },
                    progress.stopsLeft,
                    trip.warned,
                    h.shown?.channelId,
                ),
            )
        }
        fun at(minute: Double) = moments.first { it.minute >= minute }

        // Fixes first: times backed, no "~"
        assertEquals(TripSource.FIX, at(1.0).source)
        assertFalse(at(1.0).title, "~" in at(1.0).title)
        // No fixes: the rider's bus, matched by position, stands in, the decoys aside
        assertEquals(TripSource.BUS, at(3.0).source)
        assertFalse(at(3.0).title, "~" in at(3.0).title)
        assertEquals("32051", h.desk.running.value?.ride?.bus?.busNumber ?: h.desk.ended.value?.journey?.ride?.bus?.busNumber)
        // Neither: the estimate moves the rider on, and the times read "~"
        assertEquals(TripSource.ESTIMATE, at(5.5).source)
        assertTrue(at(5.5).title, "~" in at(5.5).title)
        // Fixes again
        assertEquals(TripSource.FIX, at(7.0).source)

        // The progress never went back, nor the stops left up
        for ((a, b) in moments.zipWithNext()) assertTrue("$a then $b", b.stopsLeft <= a.stopsLeft)
        // Ended past the stop, where the bus was
        assertTrue(h.desk.ended.value!!.journey.ride!!.confirmed >= 2800.0)

        // Two warnings: two stops before Pampalinda, then as it is next, on the silent warning channel
        val warnings = moments.map { it.warned }.distinct()
        assertEquals(listOf(Trip.NO_WARNING, Trip.WARN_TWO_STOPS, Trip.WARN_NEXT), warnings)
        val first = moments.first { it.warned == Trip.WARN_TWO_STOPS }
        assertEquals("Get off at Pampalinda, 3 min", first.title)
        assertEquals(TripChannel.WARNING_SILENT.id, first.channel)
        // 2000 m at 5 m/s: 400 s
        assertEquals(6.75, first.minute, 0.5)
        val second = moments.first { it.warned == Trip.WARN_NEXT }
        assertEquals("E21, get off at the next stop", second.title)
        assertEquals(8.0, second.minute, 0.5)

        // Passed Pampalinda, 2860 m at 5 m/s: about 9.5 minutes in
        h.assertEnded(TripEnding.REACHED)
        assertEquals(9.75, moments.last().minute, 0.5)
    }

    @Test
    fun theTimesFollowTheRide() {
        h.source.answer = ::buses
        h.start(plan)
        h.ride(3 * MINUTE) { along(it) }
        val trip = checkNotNull(h.desk.running.value?.ride)
        // The last fix 825 m in, 1975 m to go: the planner's estimate for them, of 85.7 s every 400 m
        val left = trip.millisTo(7, h.now()) / 1000.0
        assertEquals(1975 / 400.0 * (400 * 1.15 / 7 + 20), left, 2.0)
        assertEquals("Get off at Pampalinda, 7 min", with(h) { checkNotNull(shown).title() })
    }
}
