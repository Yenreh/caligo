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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The trip service on virtual time, with the device's fixes and the
 * line's buses as a test scripts them. [immediate] runs it as the main
 * thread's immediate dispatcher does: a coroutine started runs at once,
 * up to where it first suspends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripHarness(private val immediate: Boolean = false) {
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

    /** How many times the waits' own service was asked to start */
    var waitServiceStarts = 0
        private set

    init {
        desk.source = source
        desk.location = location
        desk.now = now
        desk.coroutineContext = dispatcher()
        desk.learning = CoroutineScope(SupervisorJob() + dispatcher())
    }

    private fun dispatcher() = if (immediate) UnconfinedTestDispatcher(scheduler) else StandardTestDispatcher(scheduler)

    fun tearDown() {
        controller?.destroy()
        waitController?.destroy()
    }

    /** The system destroys the trip service, as after a trip ends */
    fun destroyService() {
        checkNotNull(controller).destroy()
        controller = null
    }

    /** The waits on the same virtual time, their answers by [answer] */
    fun waitsAnswering(answer: (com.yenreh.caligo.domain.AskPoint, Long) -> com.yenreh.caligo.domain.WaitAnswer) {
        val waits = app.container.waits
        waits.source = com.yenreh.caligo.waits.FakeWaitSource(answer, now)
        waits.now = now
        waits.coroutineContext = dispatcher()
    }

    /** Run the waits' service as the system would, for each start asked of it */
    private fun serveWaits() {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: return
            if (intent.component?.className != com.yenreh.caligo.waits.WaitService::class.java.name) continue
            waitServiceStarts++
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

    /** [millis] of fixes every 15 s where [pointAt] puts the rider, none where it gives null, while a trip runs or finishes */
    fun move(millis: Long, pointAt: (Long) -> com.yenreh.caligo.domain.GeoPoint?) {
        val end = now() + millis
        while (now() < end && (desk.running.value != null || desk.finishing.value != null)) {
            pointAt(now())?.let { location.emit(com.yenreh.caligo.domain.TripFix(it, 10.0, now())) }
            advance(minOf(FIX_EVERY, end - now()))
        }
    }

    /** The lines whose buses the trip awaits now, soonest first */
    val awaitedLines: List<String> get() = service.tripWaitEngine.waits.sortedBy { it.last.arrivalTime }.map { it.last.line }

    /** CaliGo's notifications on show that are not the rider's own waits' */
    val tripOwn: List<Notification>
        get() = shadowOf(manager).activeNotifications.filter { it.id !in com.yenreh.caligo.waits.WaitNotifications.ids }.map { it.notification }

    /** Press the trip notification's button titled [title] */
    fun pressTitled(title: String) {
        checkNotNull(shown).actions.first { it.title.toString() == title }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The waits' notifications on show, by title: by their ids, as the trip's own may post on a wait's alert channel */
    val waitTitles: List<String>
        get() = shadowOf(manager).activeNotifications.filter { it.id in com.yenreh.caligo.waits.WaitNotifications.ids }.map { it.notification.title() }

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

    /** The last word of a trip that ended by itself, if on show */
    val endedNotice: Notification?
        get() = shadowOf(manager).activeNotifications.firstOrNull { it.id == TripNotifications.ENDED_ID }?.notification

    /**
     * The trip ended as [ending], and nothing of it is left: no
     * notification, service, request, fix asked or wake lock. Only its
     * last word when [noticed]: not ongoing, gone by itself soon.
     */
    fun assertEnded(ending: TripEnding, noticed: Boolean = ending in EndedNotice.NOTICED) {
        assertEquals(ending, desk.ended.value?.ending)
        assertNull("no trip", desk.running.value)
        assertNull("its notification gone", shown)
        val last = endedNotice
        if (noticed) {
            checkNotNull(last) { "no last word" }
            assertEquals(0, last.flags and Notification.FLAG_ONGOING_EVENT)
            assertEquals(TripNotifications.ENDED_TIMEOUT_MS, last.timeoutAfter)
        } else {
            assertNull("no last word", last)
        }
        assertTrue(shadowOf(manager).activeNotifications.none { it.id != TripNotifications.ENDED_ID && it.notification.channelId?.startsWith("trip") == true })
        assertTrue("its wait engine idle", service.tripWaitEngine.isIdle && service.tripWaitEngine.waits.isEmpty())
        val shadow = shadowOf(service)
        assertTrue("stopped", shadow.isStoppedBySelf)
        assertTrue("out of the foreground", shadow.isForegroundStopped)
        assertTrue("nothing scheduled", service.tripEngine.isIdle)
        assertNull("no fixes asked", location.requested)
        assertNull("nothing heard of a ride kept", desk.rideBuses.value)
        assertFalse("wake lock released", service.holdsWakeLock)
        // Nothing asks any more, however long it goes
        val asked = source.asked.size
        advance(3 * 60 * 60_000L)
        assertEquals(asked, source.asked.size)
        assertNull(shown)
    }

    /**
     * The trip just finished, Undo able to take it back: shown as finished,
     * not as running, under one notification, "Trip finished" with the
     * trip's title and Undo alone, silent, neither ongoing nor a Live Update
     * and gone soon by itself; underneath, the service still in the
     * foreground, its fixes asked and its waits running, as they were
     */
    fun assertFinishing(title: String) {
        // Shown as the trip's beat next runs
        scheduler.runCurrent()
        val finishing = checkNotNull(desk.finishing.value) { "not finishing" }
        assertNull("not shown as running", desk.running.value)
        assertEquals(TripEnding.FINISHED, desk.ended.value?.ending)
        val shown = checkNotNull(shown) { "no notification" }
        assertEquals("Trip finished", shown.title())
        assertEquals(title, shown.text())
        assertEquals(listOf("Undo"), shown.actions.map { it.title.toString() })
        assertEquals(TripChannel.TRIPS.id, shown.channelId)
        assertEquals(0, shown.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("never alerts", shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull("no chip", androidx.core.app.NotificationCompat.getShortCriticalText(shown))
        assertFalse("no Live Update", androidx.core.app.NotificationCompat.isRequestPromotedOngoing(shown))
        assertTrue(shown.timeoutAfter in 1..TripEngine.UNDO_WINDOW.inWholeMilliseconds + FinishingNotice.MARGIN_MS)
        assertTrue("gone after the Finish takes effect", now() + shown.timeoutAfter >= finishing.until)
        assertEquals("one notification of the trip's", 1, tripOwn.size)
        val shadow = shadowOf(service)
        assertFalse("in the foreground", shadow.isForegroundStopped)
        assertTrue("fixes asked", location.requested != null)
        assertTrue("wake lock held", service.holdsWakeLock)
    }

    /** The trip runs, its notification the service's own */
    fun assertRunning() {
        scheduler.runCurrent()
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
