package com.yenreh.caligo.trips

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.MainActivity
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.channelOn
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.bus
import com.yenreh.caligo.trips.TripFixtures.plan
import com.yenreh.caligo.waits.FakeWaitSource
import com.yenreh.caligo.waits.WaitChannel
import com.yenreh.caligo.waits.WaitFixtures
import com.yenreh.caligo.waits.WaitNotifications
import com.yenreh.caligo.waits.WaitService
import com.yenreh.caligo.waits.WaitStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The trip service and every way a trip ends, on virtual time with
 * scripted fixes and buses. After each ending it checks nothing is left:
 * no notification, no running or foreground service, no request, no
 * fixes asked, no wake lock, nothing asked however long it goes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TripServiceTest {
    private val h = TripHarness()

    @After
    fun tearDown() = h.tearDown()

    /** The rider's bus moving on at 7 m/s from the boarding stop */
    private fun along(at: Long) = (at - TripFixtures.NOW) * 7.0 / 1000

    // Reached

    @Test
    fun passingTheStopToGetOffAtEndsTheTrip() {
        h.start(plan)
        h.assertRunning()
        h.ride(10 * MINUTE) { along(it) }
        h.assertEnded(TripEnding.REACHED)
        assertEquals("Pampalinda", h.desk.ended.value!!.journey.ride!!.plan.stops[7].name)
    }

    @Test
    fun whatTheRideTaughtIsKeptThoughTheServiceGoesFirst() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.source.learnGate = gate
        h.start(plan)
        h.ride(10 * MINUTE) { along(it) }
        h.assertEnded(TripEnding.REACHED)
        // The service gone, its scope with it, before the reading is written
        h.destroyService()
        assertTrue(h.source.observed.isEmpty())
        gate.complete(Unit)
        h.advance(1)
        assertEquals(listOf("E21"), h.source.observed.flatMap { it.keys })
    }

    @Test
    fun standingAtTheStopToGetOffAtEndsTheTrip() {
        h.start(plan)
        h.ride(7 * MINUTE) { minOf(along(it), 2790.0) }
        h.assertRunning()
        h.ride(3 * MINUTE) { 2790.0 }
        h.assertEnded(TripEnding.REACHED)
    }

    // Finish, and its Undo

    private val window = TripEngine.UNDO_WINDOW.inWholeMilliseconds
    private val title = "E21 to Universidades"

    @Test
    fun finishOnTheNotificationEndsTheTripOnceItsUndoRunsOut() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        val shown = checkNotNull(h.shown)
        assertEquals("Finish", shown.actions.single().title.toString())
        h.press(0)
        h.assertFinishing(title)
        h.ride(window - 1_000) { along(it) }
        h.assertFinishing(title)
        h.ride(1_000) { along(it) }
        // As Finish ended it before: no last word
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun finishOnTheScreenEndsTheTripOnceItsUndoRunsOut() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.desk.finish()
        h.assertFinishing(title)
        assertEquals(h.now() + window, h.desk.finishing.value?.until)
        h.advance(window)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun undoOnTheNotificationTakesTheTripBack() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.pressTitled("Finish")
        h.assertFinishing(title)
        // From the lock screen too: a broadcast, no activity
        h.pressTitled("Undo")
        h.assertRunning()
        assertNull(h.desk.finishing.value)
        assertNull(h.desk.ended.value)
        assertTrue(with(h) { checkNotNull(h.shown).title() }.startsWith("Get off at Pampalinda, "))
        assertEquals(listOf("Finish"), checkNotNull(h.shown).actions.map { it.title.toString() })
        // Well past the window it still runs, then reaches its stop
        h.ride(window * 2) { along(it) }
        h.assertRunning()
        h.ride(10 * MINUTE) { along(it) }
        h.assertEnded(TripEnding.REACHED)
    }

    @Test
    fun undoOnTheScreenTakesTheTripBack() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.desk.finish()
        h.ride(window / 2) { along(it) }
        h.desk.undoFinish()
        h.advance(1)
        h.assertRunning()
        assertEquals(plan, h.desk.running.value?.ride?.plan)
        h.ride(window) { along(it) }
        h.assertRunning()
        h.desk.finish()
        h.advance(window)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun finishingNothingWarnsAndAnUndoStillGivesTheWarning() {
        h.start(plan)
        h.ride(5 * MINUTE) { along(it) }
        val warned = h.desk.running.value?.ride?.warned
        h.desk.finish()
        // The stop to get off at is next: its warning waits
        h.ride(window - 1_000) { 2410.0 }
        h.assertFinishing(title)
        assertEquals(warned, h.desk.finishing.value?.journey?.ride?.warned)
        h.desk.undoFinish()
        h.advance(1)
        val shown = checkNotNull(h.shown)
        assertEquals("E21, get off at the next stop", with(h) { shown.title() })
        assertEquals(TripChannel.WARNING_SILENT.id, shown.channelId)
        assertEquals("it alerts", 0, shown.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun aSwipeOfTheFinishedNotificationEndsTheTripAtOnce() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.desk.finish()
        h.assertFinishing(title)
        h.swipe()
        assertNull(h.desk.finishing.value)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun aTripThatWouldEndByItselfWhileFinishingEndsAsFinishedWithNoLastWord() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.pressTitled("Finish")
        h.location.lose()
        h.advance(1)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun aRideThatReachesItsStopWhileFinishingEndsAsFinished() {
        h.start(plan)
        h.ride(6 * MINUTE + 30_000) { along(it) }
        h.desk.finish()
        h.ride(window - 1_000) { 2900.0 }
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun anotherRideStartedWhileFinishingEndsItAsFinished() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.desk.finish()
        h.start(plan.copy(alight = 3))
        assertEquals(TripEnding.FINISHED, h.desk.ended.value?.ending)
        assertNull(h.desk.finishing.value)
        h.assertRunning()
        assertEquals(1, h.posted.count { it.channelId?.startsWith("trip") == true })
        assertEquals(3, h.desk.running.value?.ride?.plan?.alight)
        // Its window runs out on nothing: the new one runs on
        h.ride(window * 2) { along(it) }
        h.assertRunning()
    }

    @Test
    fun aFinishAgainChangesNothing() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.desk.finish()
        val until = h.desk.finishing.value?.until
        h.advance(window / 2)
        h.desk.finish()
        // A Finish left on the shade, as before its Undo showed
        h.app.sendBroadcast(
            Intent(h.app, TripActionReceiver::class.java)
                .setAction(TripNotifications.ACTION_FINISH)
                .putExtra(TripNotifications.EXTRA_STARTED_AT, checkNotNull(h.desk.finishing.value).journey.startedAt),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(until, h.desk.finishing.value?.until)
        h.advance(window / 2)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun aFinishBeforeTheServiceTookTheStartEndsItAtOnce() {
        h.desk.start(plan)
        h.desk.finish()
        assertNull(h.desk.running.value)
        assertNull(h.desk.finishing.value)
        assertEquals(TripEnding.FINISHED, h.desk.ended.value?.ending)
        // The service, started meanwhile, finds nothing to follow
        h.start(null)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun anUndoLeftFromAnEarlierTripTakesNoNewerOneBack() {
        h.start(plan)
        h.desk.finish()
        h.app.sendBroadcast(
            Intent(h.app, TripActionReceiver::class.java)
                .setAction(TripNotifications.ACTION_UNDO)
                .putExtra(TripNotifications.EXTRA_STARTED_AT, 1L),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        h.assertFinishing(title)
    }

    @Test
    fun aFinishLeftFromAnEarlierTripEndsNoNewerOne() {
        h.start(plan)
        h.app.sendBroadcast(
            Intent(h.app, TripActionReceiver::class.java)
                .setAction(TripNotifications.ACTION_FINISH)
                .putExtra(TripNotifications.EXTRA_STARTED_AT, 1L),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        h.assertRunning()
    }

    // Swiped away

    @Test
    fun swipedAway() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.swipe()
        h.assertEnded(TripEnding.DISMISSED)
    }

    // Two hours

    @Test
    fun twoHours() {
        h.start(plan)
        // Held at the boarding stop by its fixes, as a bus stuck
        h.ride(Trip.TIME_LIMIT.inWholeMilliseconds - MINUTE) { 10.0 }
        h.assertRunning()
        h.ride(2 * MINUTE) { 10.0 }
        h.assertEnded(TripEnding.TIME_LIMIT)
        assertEquals("The trip ended: it ran too long.", with(h) { checkNotNull(h.endedNotice).text() })
    }

    // Off the route for good

    @Test
    fun offTheRouteForGood() {
        h.start(plan)
        h.ride(2 * MINUTE) { along(it) }
        // Off the bus and walking away, a few blocks north
        h.ride(Trip.OFF_ROUTE_FOR.inWholeMilliseconds - 20_000, north = 500.0) { 900.0 }
        h.assertRunning()
        assertEquals("E21 · Away from the route", with(h) { checkNotNull(shown).text() })
        h.ride(MINUTE, north = 500.0) { 900.0 }
        h.assertEnded(TripEnding.OFF_ROUTE)
        // Its last word peeks as the alerts are set: the stop to get off at is still ahead
        val last = checkNotNull(h.endedNotice)
        assertEquals(TripChannel.WARNING_SILENT.id, last.channelId)
        assertEquals("Trip ended: watch for your stop", with(h) { last.title() })
        assertEquals("You left the route, so the trip ended.", with(h) { last.text() })
        assertTrue(last.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val intent = shadowOf(last.contentIntent).savedIntent
        assertEquals(TripNotifications.ACTION_OPEN_TRIP, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun followingTheLineOnlyItsLastWordIsSilent() {
        h.start(plan.copy(alight = null))
        h.ride(2 * MINUTE) { along(it) }
        h.ride(Trip.OFF_ROUTE_FOR.inWholeMilliseconds + MINUTE, north = 500.0) { 900.0 }
        h.assertEnded(TripEnding.OFF_ROUTE)
        val last = checkNotNull(h.endedNotice)
        assertEquals(TripChannel.TRIPS.id, last.channelId)
        assertEquals("Trip ended", with(h) { last.title() })
    }

    @Test
    fun withTheWarningChannelOffTheLastWordIsSilent() {
        TripNotifications.createChannels(h.app)
        h.manager.getNotificationChannel(TripChannel.WARNING_SILENT.id).importance = NotificationManager.IMPORTANCE_NONE
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.location.lose()
        h.advance(1)
        h.assertEnded(TripEnding.NO_LOCATION)
        val last = checkNotNull(h.endedNotice)
        assertEquals(TripChannel.TRIPS.id, last.channelId)
        assertEquals("Without your location the ride cannot be followed.", with(h) { last.text() })
    }

    @Test
    fun withAlertsThatSoundTheLastWordGoesOnTheirChannel() {
        h.desk.alerts = { com.yenreh.caligo.waits.Alerts(sound = true, vibrate = true) }
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.location.lose()
        h.advance(1)
        h.assertEnded(TripEnding.NO_LOCATION)
        assertEquals(TripChannel.WARNING_SOUND_VIBRATE.id, checkNotNull(h.endedNotice).channelId)
    }

    @Test
    fun theNextStartTakesTheLastWordAway() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.location.lose()
        h.advance(1)
        h.assertEnded(TripEnding.NO_LOCATION)
        h.location.usable = true
        h.start(plan)
        assertNull(h.endedNotice)
        h.assertRunning()
    }

    // Notifications or location turned off

    @Test
    fun notificationsTurnedOff() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        shadowOf(h.manager).setNotificationsEnabled(false)
        h.advance(TripEngine.BEAT.inWholeMilliseconds)
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun theTripsChannelTurnedOff() {
        h.start(plan)
        h.manager.getNotificationChannel(TripChannel.TRIPS.id).importance = NotificationManager.IMPORTANCE_NONE
        h.advance(TripEngine.BEAT.inWholeMilliseconds)
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun theWarningChannelTurnedOffOnlySilencesTheWarning() {
        TripNotifications.createChannels(h.app)
        h.manager.getNotificationChannel(TripChannel.WARNING_SILENT.id).importance = NotificationManager.IMPORTANCE_NONE
        h.start(plan)
        h.ride(6 * MINUTE + 30_000) { along(it) }
        h.assertRunning()
        val shown = checkNotNull(h.shown)
        assertEquals(TripChannel.TRIPS.id, shown.channelId)
        assertTrue(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun locationTurnedOff() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.location.lose()
        h.advance(1)
        h.assertEnded(TripEnding.NO_LOCATION)
    }

    @Test
    fun locationPermissionTakenBack() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        // Seen at the next beat, as the permission has no callback
        h.location.usable = false
        h.advance(TripEngine.BEAT.inWholeMilliseconds)
        h.assertEnded(TripEnding.NO_LOCATION)
    }

    @Test
    fun withoutTheLocationPermissionTheTripEndsAtOnce() {
        // The real check, on the permission itself: without it Android throws on startForeground
        shadowOf(h.app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        h.location.usable = false
        h.start(plan)
        h.advance(1)
        // Ended as it starts, the screen on show: no last word
        h.assertEnded(TripEnding.NO_LOCATION, noticed = false)
    }

    // The process going away

    @Test
    fun aNewProcessLeavesNoTripNorNotification() {
        TripNotifications.createChannels(h.app)
        for (id in listOf(TripNotifications.TRIP_ID, TripNotifications.PLACEHOLDER_ID, TripNotifications.ENDED_ID)) {
            h.manager.notify(id, Notification.Builder(h.app, TripChannel.TRIPS.id).setSmallIcon(android.R.drawable.ic_dialog_info).build())
        }
        TripNotifications.cancelLeftovers(h.app)
        assertTrue(h.posted.isEmpty())
        assertNull(h.desk.running.value)
    }

    @Test
    fun theServiceIsNeverSticky() {
        h.start(plan)
        val result = h.service.onStartCommand(null, 0, 99)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
    }

    // Replaced

    @Test
    fun anotherRideReplacesTheTrip() {
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        h.start(plan.copy(alight = 3))
        assertEquals(TripEnding.REPLACED, h.desk.ended.value?.ending)
        h.assertRunning()
        assertEquals(1, h.posted.count { it.channelId?.startsWith("trip") == true })
        assertEquals(3, h.desk.running.value?.ride?.plan?.alight)
    }

    // Waits and trips at once

    private fun startWait(): WaitService {
        val waits = h.app.container.waits
        waits.source = FakeWaitSource({ _, at -> WaitFixtures.answer(at, WaitFixtures.t31) }, h.now)
        waits.now = h.now
        waits.coroutineContext = StandardTestDispatcher(h.scheduler)
        assertEquals(WaitStart.STARTED, waits.start(WaitFixtures.awaited(WaitFixtures.t31), null))
        val controller = Robolectric.buildService(WaitService::class.java).create().startCommand(0, 1)
        h.scheduler.runCurrent()
        return controller.get()
    }

    @Test
    fun aTripThatEndsLeavesTheWaitsRunning() {
        val wait = startWait()
        h.start(plan)
        assertEquals(2, h.posted.size)
        h.desk.finish()
        h.advance(TripEngine.UNDO_WINDOW.inWholeMilliseconds)
        // The wait's notification and service stay as they were
        assertEquals(listOf(WaitChannel.WAITS.id), h.posted.map { it.channelId })
        assertFalse(shadowOf(wait).isStoppedBySelf)
        assertFalse(shadowOf(wait).isForegroundStopped)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun aWaitThatEndsLeavesTheTripRunning() {
        val wait = startWait()
        h.start(plan)
        h.app.container.waits.stop(WaitFixtures.awaited(WaitFixtures.t31).key)
        h.advance(1)
        assertTrue(shadowOf(wait).isStoppedBySelf)
        assertTrue(h.posted.none { it.channelId == WaitChannel.WAITS.id })
        h.assertRunning()
    }

    // Precise location

    @Test
    fun theApproximatePermissionAloneIsReadAtEveryRound() {
        h.location.precise = false
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        assertEquals(true, h.desk.running.value?.approximate)
        // Made precise meanwhile, in the system's settings
        h.location.precise = true
        h.ride(TripEngine.BEAT.inWholeMilliseconds) { along(it) }
        assertEquals(false, h.desk.running.value?.approximate)
        h.assertRunning()
    }

    // The notification

    @Test
    fun itIsALiveUpdateWithTheMinutesAsItsChip() {
        h.start(plan)
        val shown = checkNotNull(h.shown)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(shown))
        assertTrue(shown.hasPromotableCharacteristics())
        assertEquals(null, shown.group)
        assertEquals("~10${NBSP}min", NotificationCompat.getShortCriticalText(shown))
        assertEquals("Get off at Pampalinda, ~10${NBSP}min", with(h) { shown.title() })
        assertEquals("E21 · 7${NBSP}stops · next: Centro", with(h) { shown.text() })
        // No clock: the minutes are in the title and the chip
        assertFalse(NotificationCompat.getUsesChronometer(shown))
        assertFalse(NotificationCompat.getShowWhen(shown))
        val style = Notification.ProgressStyle::class.java
        assertEquals(style.name, shown.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun itsPointsAreTheLastStopsBeforeGettingOff() {
        h.start(plan)
        val style = Notification.Builder.recoverBuilder(h.app, checkNotNull(h.shown)).style as Notification.ProgressStyle
        assertEquals(TripNotice.MAX_POINTS, style.progressPoints.size)
        assertEquals(listOf(1200, 1600, 2000, 2400), style.progressPoints.map { (it.position + 5) / 10 * 10 })
    }

    @Test
    fun tappingItOpensTheTripScreenInTheOneActivity() {
        h.start(plan)
        val intent = shadowOf(checkNotNull(h.shown).contentIntent).savedIntent
        assertEquals(TripNotifications.ACTION_OPEN_TRIP, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun itGoesByItselfSoonAfterItsNextUpdateIsDue() {
        h.start(plan)
        h.advance(1)
        val timeout = checkNotNull(h.shown).timeoutAfter
        assertTrue(timeout > 0)
        assertTrue(timeout <= TripEngine.BEAT.inWholeMilliseconds + TripNotice.TIMEOUT_MARGIN_MS)
    }

    @Test
    fun theBusesAreAskedEvery30sAfterEachAnswer() {
        h.source.answer = { listOf(bus("32051", along(it))) }
        h.start(plan)
        assertEquals(1, h.source.asked.size)
        h.advance(29_000)
        assertEquals(1, h.source.asked.size)
        h.advance(1_000)
        assertEquals(2, h.source.asked.size)
        h.advance(60_000)
        assertEquals(4, h.source.asked.size)
    }

    @Test
    fun fixesAreAskedBalancedFarAndHighNearTheStop() {
        h.start(plan)
        assertEquals(com.yenreh.caligo.data.location.FixQuality.BALANCED, h.location.requested)
        h.ride(6 * MINUTE + 30_000) { along(it) }
        assertEquals(com.yenreh.caligo.data.location.FixQuality.HIGH, h.location.requested)
        assertEquals(
            listOf(com.yenreh.caligo.data.location.FixQuality.BALANCED, com.yenreh.caligo.data.location.FixQuality.HIGH),
            h.location.qualities.distinct(),
        )
    }

    @Test
    fun theTripsChannelShowsOnTheLockScreenYetNeverMakesANoise() {
        TripNotifications.createChannels(h.app)
        // Default, as a lock screen that hides silent notifications still shows it
        val channel = h.manager.getNotificationChannel(TripChannel.TRIPS.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        // The warnings keep theirs
        assertEquals(NotificationManager.IMPORTANCE_HIGH, h.manager.getNotificationChannel(TripChannel.WARNING_SILENT.id).importance)
        h.start(plan)
        h.ride(MINUTE) { along(it) }
        val shown = checkNotNull(h.shown)
        assertEquals(TripChannel.TRIPS.id, shown.channelId)
        assertTrue(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun theLowTripsChannelOfBeforeGoes() {
        h.manager.createNotificationChannel(NotificationChannel("trips", "Trips", NotificationManager.IMPORTANCE_LOW))
        TripNotifications.createChannels(h.app)
        assertNull(h.manager.getNotificationChannel("trips"))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, h.manager.getNotificationChannel(TripChannel.TRIPS.id).importance)
    }

    @Test
    fun aTripsChannelTurnedOffBeforeStaysOff() {
        h.manager.createNotificationChannel(NotificationChannel("trips", "Trips", NotificationManager.IMPORTANCE_NONE))
        // Read from the old one before the new is made
        assertFalse(channelOn(h.app, TripChannel.TRIPS))
        TripNotifications.createChannels(h.app)
        assertNull(h.manager.getNotificationChannel("trips"))
        assertEquals(NotificationManager.IMPORTANCE_NONE, h.manager.getNotificationChannel(TripChannel.TRIPS.id).importance)
        assertFalse(channelOn(h.app, TripChannel.TRIPS))
        h.start(plan)
        h.advance(TripEngine.BEAT.inWholeMilliseconds)
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun theChannelsAreMadeOnceAndNeverAltered() {
        TripNotifications.createChannels(h.app)
        h.manager.getNotificationChannel(TripChannel.WARNING_SOUND.id).importance = NotificationManager.IMPORTANCE_LOW
        TripNotifications.createChannels(h.app)
        assertEquals(NotificationManager.IMPORTANCE_LOW, h.manager.getNotificationChannel(TripChannel.WARNING_SOUND.id).importance)
        // Apart from the waits'
        assertEquals(TripChannel.entries.size, h.manager.notificationChannels.count { it.id.startsWith("trip") })
        for (id in listOf(TripNotifications.TRIP_ID, TripNotifications.PLACEHOLDER_ID, TripNotifications.ENDED_ID)) assertTrue(id !in WaitNotifications.ids)
    }
}
