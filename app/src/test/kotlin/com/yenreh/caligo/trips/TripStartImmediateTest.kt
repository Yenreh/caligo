package com.yenreh.caligo.trips

import android.Manifest
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.plan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The trip service on a dispatcher that runs a coroutine at once, as the
 * main thread's immediate one does: the trip's first round runs inside
 * its start, so a trip may end before the start returns. The service is
 * in the foreground before that, and ends clean.
 */
@RunWith(AndroidJUnit4::class)
class TripStartImmediateTest {
    private val h = TripHarness(immediate = true)

    @After
    fun tearDown() = h.tearDown()

    /** The rider's bus moving on at 7 m/s from the boarding stop */
    private fun along(at: Long) = (at - TripFixtures.NOW) * 7.0 / 1000

    private fun tripsChannelOff() {
        TripNotifications.createChannels(h.app)
        h.manager.getNotificationChannel(TripChannel.TRIPS.id).importance = NotificationManager.IMPORTANCE_NONE
    }

    @Test
    fun aRideWithTheTripsChannelOffEndsAtItsStart() {
        tripsChannelOff()
        h.start(plan)
        assertEquals(TripNotifications.TRIP_ID, shadowOf(h.service).lastForegroundNotificationId)
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun aPlannerTripWithTheTripsChannelOffEndsAtItsStart() {
        tripsChannelOff()
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun aTripWithoutLocationEndsAtItsStart() {
        shadowOf(h.app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        h.location.usable = false
        h.start(plan)
        h.assertEnded(TripEnding.NO_LOCATION)
    }

    @Test
    fun aRideRunsToItsStop() {
        h.start(plan)
        h.assertRunning()
        h.ride(10 * MINUTE) { along(it) }
        h.assertEnded(TripEnding.REACHED)
    }

    @Test
    fun anotherRideReplacesTheTrip() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.start(plan.copy(alight = 3))
        assertEquals(TripEnding.REPLACED, h.desk.ended.value?.ending)
        h.assertRunning()
        h.desk.finish()
        h.assertEnded(TripEnding.FINISHED)
    }
}
