package com.yenreh.caligo.trips

import android.app.NotificationManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.firstArrivals
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.JourneyFixtures.origin
import com.yenreh.caligo.trips.JourneyFixtures.secondArrivals
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.stops.FakeNotificationAccess
import com.yenreh.caligo.ui.trips.TripMessage
import com.yenreh.caligo.ui.trips.tripNeeds
import com.yenreh.caligo.waits.WaitChannel
import com.yenreh.caligo.waits.WaitNotifications
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * What lets a trip post: the app's notifications and the trips' own
 * channel, never the waits' one, though the trip carries its awaited
 * buses
 */
@RunWith(AndroidJUnit4::class)
class TripAccessTest {
    private val h = TripHarness()
    private val location = FakeLocationSource(permission = LocationPermission.GRANTED)
    private val notifications = FakeNotificationAccess(granted = true)

    @Before
    fun setUp() {
        h.source.arrivalsAnswer = { stops, at -> if (stops.any { it.stopId == TripFixtures.out[0].stopId }) firstArrivals(at) else secondArrivals(at) }
        h.waitsAnswering { _, at -> WaitAnswer(firstArrivals(NOW).stops, at) }
        TripNotifications.createChannels(h.app)
        WaitNotifications.createChannels(h.app)
    }

    @After
    fun tearDown() = h.tearDown()

    private fun turnOff(id: String) {
        h.manager.getNotificationChannel(id).importance = NotificationManager.IMPORTANCE_NONE
    }

    private fun needs() = runBlocking { tripNeeds(notifications, location, h.desk) }

    @Test
    fun withTheWaitsChannelOffATripStartsAndShowsItsAwaitedBuses() {
        turnOff(WaitChannel.WAITS.id)
        assertNull(needs())
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
        h.move(MINUTE) { origin }
        h.assertRunning()
        assertEquals(listOf("E21", "T31"), h.awaitedLines)
        assertEquals(listOf("E21", "T31"), h.desk.awaited.value.map { it.line })
        with(h) { assertEquals(true, Regex("E21 \\d+${NBSP}min").containsMatchIn(checkNotNull(shown).text())) }
        // Still there a while later: never ended as blocked, nor asked for again
        val asked = h.source.arrivalsAsked.size
        h.move(2 * MINUTE) { origin }
        assertEquals(listOf("E21", "T31"), h.awaitedLines)
        assertEquals(asked, h.source.arrivalsAsked.size)
    }

    @Test
    fun withTheTripsChannelOffNoTripStartsAndItsChannelSettingsOpen() {
        turnOff(TripChannel.TRIPS.id)
        assertEquals(TripMessage.NEEDS_NOTIFICATIONS, needs())
        h.desk.openSettings()
        val intent = checkNotNull(shadowOf(h.app).nextStartedActivity)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(TripChannel.TRIPS.id, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun withTheAppsNotificationsOffItsNotificationSettingsOpen() {
        shadowOf(h.manager).setNotificationsEnabled(false)
        assertEquals(TripMessage.NEEDS_NOTIFICATIONS, needs())
        h.desk.openSettings()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, checkNotNull(shadowOf(h.app).nextStartedActivity).action)
    }
}
