package com.yenreh.caligo.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.MainActivity
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripNotifications
import com.yenreh.caligo.waits.WaitNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The trip screen a notification's intent opens, and none from Recents, where the intent is the old one */
@RunWith(AndroidJUnit4::class)
class OpenedTripIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val took = TripFixtures.took

    private fun tookIt() = Intent(context, MainActivity::class.java)
        .setAction(WaitNotifications.ACTION_TOOK)
        .putExtra(WaitNotifications.EXTRA_STOP_ID, took.stopId)
        .putExtra(WaitNotifications.EXTRA_VEHICLE_ID, took.vehicleId)
        .putExtra(WaitNotifications.EXTRA_TRIP, took.trip)
        .putExtra(WaitNotifications.EXTRA_STOP_NAME, took.stopName)
        .putExtra(WaitNotifications.EXTRA_LINE, took.line)
        .putExtra(WaitNotifications.EXTRA_DESTINATION, took.destination)

    private fun openTrip() = Intent(context, MainActivity::class.java).setAction(TripNotifications.ACTION_OPEN_TRIP)

    @Test
    fun iTookItOpensWhereToGetOffForItsRide() {
        assertEquals(Route.Trip(took.key), MainActivity.openedTrip(tookIt()))
    }

    @Test
    fun theTripsNotificationOpensTheTripRunning() {
        assertEquals(Route.Trip(), MainActivity.openedTrip(openTrip()))
    }

    @Test
    fun fromRecentsNeitherOpensAnything() {
        assertNull(MainActivity.openedTrip(tookIt().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertNull(MainActivity.openedTrip(openTrip().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }
}
