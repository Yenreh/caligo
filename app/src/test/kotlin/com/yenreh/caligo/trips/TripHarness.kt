package com.yenreh.caligo.trips

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.trips.TripFixtures.NOW
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The trip service on virtual time, with the device's fixes and the
 * line's buses as a test scripts them
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripHarness {
    val scheduler = TestCoroutineScheduler()
    val now = { NOW + scheduler.currentTime }
    val app = ApplicationProvider.getApplicationContext<CaliGoApp>()
    val desk = app.container.trips
    val manager: NotificationManager = app.getSystemService(NotificationManager::class.java)
    val location = FakeLocationUpdates()
    val source = FakeTripSource(now)

    private var controller: ServiceController<TripService>? = null
    private var startId = 0

    /** The waits' own service, once a trip's waits start it */
    var waitController: ServiceController<com.yenreh.caligo.waits.WaitService>? = null
        private set
    private var waitStartId = 0

    init {
        desk.source = source
        desk.location = location
        desk.now = now
        desk.coroutineContext = StandardTestDispatcher(scheduler)
    }

    fun tearDown() {
        controller?.destroy()
        waitController?.destroy()
    }

    /** The waits on the same virtual time, their answers by [answer] */
    fun waitsAnswering(answer: (com.yenreh.caligo.domain.AskPoint, Long) -> com.yenreh.caligo.domain.WaitAnswer) {
        val waits = app.container.waits
        waits.source = com.yenreh.caligo.waits.FakeWaitSource(answer, now)
        waits.now = now
        waits.coroutineContext = StandardTestDispatcher(scheduler)
    }

    /** Run the waits' service as the system would, for each start asked of it */
    private fun serveWaits() {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: return
            if (intent.component?.className != com.yenreh.caligo.waits.WaitService::class.java.name) continue
            val running = waitController
            waitController = if (running == null || shadowOf(running.get()).isStoppedBySelf) {
                (running ?: Robolectric.buildService(com.yenreh.caligo.waits.WaitService::class.java).create()).startCommand(0, ++waitStartId)
            } else {
                running.startCommand(0, ++waitStartId)
            }
            scheduler.runCurrent()
        }
    }

    val service: TripService get() = checkNotNull(controller).get()

    /** Start [plan] as the trip screen does, the service taking it; null takes the start already asked */
    fun start(plan: TripPlan?) {
        plan?.let { desk.start(it) }
        val running = controller
        controller = if (running == null || shadowOf(running.get()).isStoppedBySelf) {
            (running ?: Robolectric.buildService(TripService::class.java, Intent(app, TripService::class.java)).create())
                .startCommand(0, ++startId)
        } else {
            running.startCommand(0, ++startId)
        }
        scheduler.runCurrent()
    }

    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        serveWaits()
    }

    /**
     * [millis] of riding: a fix every 15 s where [fixAt] puts the rider,
     * [north] metres off the route, none where it gives null
     */
    fun ride(millis: Long, north: Double = 0.0, fixAt: (Long) -> Double?) {
        val end = now() + millis
        while (now() < end) {
            fixAt(now())?.let { location.emit(TripFixtures.fix(it, now(), north = north)) }
            advance(minOf(FIX_EVERY, end - now()))
        }
    }

    /** [millis] of fixes every 15 s where [pointAt] puts the rider, none where it gives null */
    fun move(millis: Long, pointAt: (Long) -> com.yenreh.caligo.domain.GeoPoint?) {
        val end = now() + millis
        while (now() < end && desk.running.value != null) {
            pointAt(now())?.let { location.emit(com.yenreh.caligo.domain.TripFix(it, 10.0, now())) }
            advance(minOf(FIX_EVERY, end - now()))
        }
    }

    /** The waits' notifications on show, by title */
    val waitTitles: List<String> get() = posted.filter { it.channelId == "waits" || it.channelId?.startsWith("alert") == true }.map { it.title() }

    /** The trip's own notification, if on show */
    val shown: Notification?
        get() = shadowOf(manager).activeNotifications.firstOrNull { it.id == TripNotifications.TRIP_ID }?.notification

    val posted: List<Notification> get() = shadowOf(manager).activeNotifications.map { it.notification }

    fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    fun press(action: Int) {
        checkNotNull(shown).actions[action].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun swipe() {
        checkNotNull(shown).deleteIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The trip ended as [ending], and nothing of it is left: no notification, service, request, fix asked or wake lock */
    fun assertEnded(ending: TripEnding) {
        assertEquals(ending, desk.ended.value?.ending)
        assertNull("no trip", desk.running.value)
        assertNull("its notification gone", shown)
        assertTrue(posted.none { it.channelId?.startsWith("trip") == true })
        assertTrue("no wait of the trip's left: $waitTitles", desk.ended.value?.journey?.awaited.orEmpty().none { key -> app.container.waits.running.value.any { it.key == key } })
        val shadow = shadowOf(service)
        assertTrue("stopped", shadow.isStoppedBySelf)
        assertTrue("out of the foreground", shadow.isForegroundStopped)
        assertTrue("nothing scheduled", service.tripEngine.isIdle)
        assertNull("no fixes asked", location.requested)
        assertFalse("wake lock released", service.holdsWakeLock)
        // Nothing asks any more, however long it goes
        val asked = source.asked.size
        advance(3 * 60 * 60_000L)
        assertEquals(asked, source.asked.size)
        assertNull(shown)
    }

    /** The trip runs, its notification the service's own */
    fun assertRunning() {
        val shown = checkNotNull(shown) { "no trip notification" }
        assertTrue(desk.running.value != null)
        val shadow = shadowOf(service)
        assertFalse("in the foreground", shadow.isForegroundStopped)
        assertEquals(TripNotifications.TRIP_ID, shadow.lastForegroundNotificationId)
        assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("wake lock held", service.holdsWakeLock)
        assertTrue("fixes asked", location.requested != null)
    }

    companion object {
        const val FIX_EVERY = 15_000L
    }
}
