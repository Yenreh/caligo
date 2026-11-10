package com.yenreh.caligo.waits

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.waits.WaitFixtures.MINUTE
import com.yenreh.caligo.waits.WaitFixtures.NOW
import com.yenreh.caligo.waits.WaitFixtures.OTHER_STOP
import com.yenreh.caligo.waits.WaitFixtures.answer
import com.yenreh.caligo.waits.WaitFixtures.awaited
import com.yenreh.caligo.waits.WaitFixtures.bus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The wait service and every way a wait ends, with one wait and with
 * several, on virtual time and scripted answers. After each ending it
 * checks what is left: no notification, no running service, no scheduled
 * work, no request and no wake lock beyond what the remaining waits need.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WaitServiceTest {
    private val scheduler = TestCoroutineScheduler()
    private val now = { NOW + scheduler.currentTime }
    private val app = ApplicationProvider.getApplicationContext<CaliGoApp>()
    private val desk = app.container.waits
    private val manager = app.getSystemService(NotificationManager::class.java)

    /** What the stop answers: these buses, by the time asked */
    private var listed: (Long) -> List<BusArrival> = { listOf(t31, a47) }

    /** Requests around the stop's own place fail while set */
    private var failing = false

    private val source = FakeWaitSource({ point, at ->
        if (failing && point.placed) throw com.yenreh.caligo.data.datasources.NetworkApiException("offline")
        if (point.placed) answer(at, *listed(at).toTypedArray()) else otherAnswer(at)
    }, now)

    private var otherAnswer: (Long) -> WaitAnswer = { at -> answer(at, a47Elsewhere.bus, stopId = OTHER_STOP) }

    private var controller: ServiceController<WaitService>? = null
    private var startId = 0

    private val t31 = bus("T31", "t31", NOW + 10 * MINUTE)
    private val a47 = bus("A47", "a47", NOW + 20 * MINUTE, destination = "Valle Grande")
    private val e21 = bus("E21", "e21", NOW + 4 * MINUTE, destination = "Terminal Paso del Comercio")
    private val a47Elsewhere = awaited(a47, stopId = OTHER_STOP, placed = false)

    @Before
    fun setUp() {
        desk.source = source
        desk.now = now
        desk.coroutineContext = StandardTestDispatcher(scheduler)
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private val service get() = checkNotNull(controller).get()

    /** Start waiting for each of [buses] as a bell does, the service taking the first */
    private fun start(vararg buses: AwaitedBus) {
        for (bus in buses) assertEquals(WaitStart.STARTED, desk.start(bus, null))
        val running = controller
        if (running == null || shadowOf(running.get()).isStoppedBySelf) {
            controller = (running ?: Robolectric.buildService(WaitService::class.java, Intent(app, WaitService::class.java)).create())
                .startCommand(0, ++startId)
        }
        scheduler.runCurrent()
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val posted: List<Notification> get() = shadowOf(manager).activeNotifications.map { it.notification }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun notificationOf(line: String): Notification = posted.single { line in it.title() }

    private fun press(line: String, action: Int) {
        notificationOf(line).actions[action].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun swipe(line: String) {
        notificationOf(line).deleteIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Only [lines] are left waited for, each with its notification, and nothing else runs */
    private fun assertLeft(vararg lines: String) {
        val titles = posted.map { it.title() }
        assertEquals(titles.toString(), lines.size, titles.size)
        for (line in lines) assertTrue("$line in $titles", titles.any { line in it })
        val shadow = shadowOf(service)
        assertEquals(lines.size, service.waitEngine.waits.size)
        assertEquals(lines.size, desk.running.value.size)
        if (lines.isEmpty()) {
            assertTrue("stopped", shadow.isStoppedBySelf)
            assertTrue("out of the foreground", shadow.isForegroundStopped)
            assertTrue("nothing scheduled", service.waitEngine.isIdle)
            assertFalse("wake lock released", service.holdsWakeLock)
            // Nothing asks any more, however long it goes
            val asked = source.asked.size
            advance(2 * Wait.TIME_LIMIT.inWholeMilliseconds)
            assertEquals(asked, source.asked.size)
            assertTrue(posted.isEmpty())
        } else {
            assertFalse("in the foreground", shadow.isForegroundStopped)
            val ids = shadowOf(manager).activeNotifications.map { it.id }
            assertTrue("its own notification on show", shadow.lastForegroundNotificationId in ids)
            assertTrue("wake lock held", service.holdsWakeLock)
        }
    }

    // 1. Stop waiting

    @Test
    fun stopWaiting() {
        start(awaited(t31))
        press("T31", 1)
        assertLeft()
    }

    @Test
    fun stopWaitingOneOfSeveral() {
        start(awaited(t31), awaited(a47))
        val carrier = shadowOf(service).lastForegroundNotificationId
        press("T31", 1)
        assertLeft("A47")
        // The service's own notification moved to the wait left
        assertNotEquals(carrier, shadowOf(service).lastForegroundNotificationId)
    }

    // 2. Swiped away

    @Test
    fun swipedAway() {
        start(awaited(t31))
        swipe("T31")
        assertLeft()
    }

    @Test
    fun swipedAwayOneOfSeveral() {
        start(awaited(t31), awaited(a47))
        swipe("A47")
        assertLeft("T31")
    }

    // 3. The filled bell

    @Test
    fun bellTappedAgain() {
        start(awaited(t31))
        desk.stop(awaited(t31).key)
        assertLeft()
    }

    @Test
    fun bellTappedAgainOneOfSeveral() {
        start(awaited(t31), awaited(a47))
        desk.stop(awaited(t31).key)
        assertLeft("A47")
    }

    // 4. I took it

    @Test
    fun tookIt() {
        start(awaited(t31))
        press("T31", 0)
        assertLeft()
    }

    @Test
    fun tookItOneOfSeveral() {
        start(awaited(t31), awaited(a47))
        press("A47", 0)
        assertLeft("T31")
    }

    // 5. The bus passes

    private fun e21Passes() {
        // Listed until a minute before its time, then gone: it reached the stop
        listed = { at -> if (at < e21.arrivalTime - MINUTE) listOf(e21, t31) else listOf(t31) }
    }

    @Test
    fun busPasses() {
        e21Passes()
        start(awaited(e21))
        advance(e21.arrivalTime - MINUTE + 30_000 - now())
        assertTrue(notificationOf("E21").title().endsWith("at the stop"))
        advance(e21.arrivalTime + MINUTE + 15_000 - now())
        assertEquals("Did you take the E21?", notificationOf("E21").title())
        advance(e21.arrivalTime + 2 * MINUTE + 15_000 - now())
        assertLeft()
    }

    @Test
    fun busPassesOneOfSeveral() {
        e21Passes()
        start(awaited(e21), awaited(t31))
        advance(e21.arrivalTime + 2 * MINUTE + 15_000 - now())
        assertLeft("T31")
    }

    // 6. The time limit

    private val far = bus("T31", "t31", NOW + 60 * MINUTE)
    private val farther = bus("A47", "a47", NOW + 70 * MINUTE, destination = "Valle Grande")

    @Test
    fun timeLimit() {
        listed = { listOf(far) }
        start(awaited(far))
        advance(Wait.TIME_LIMIT.inWholeMilliseconds - 15_000)
        assertEquals(1, posted.size)
        advance(30_000)
        assertLeft()
    }

    @Test
    fun timeLimitOneOfSeveral() {
        listed = { listOf(far, farther) }
        start(awaited(far))
        advance(10 * MINUTE)
        start(awaited(farther))
        advance(Wait.TIME_LIMIT.inWholeMilliseconds - 10 * MINUTE + 15_000)
        assertLeft("A47")
    }

    // 7. No longer seen

    @Test
    fun noLongerSeen() {
        listed = { listOf(a47) }
        start(awaited(t31))
        advance(MINUTE)
        assertTrue(notificationOf("T31").extras.getCharSequence(Notification.EXTRA_TEXT).toString().endsWith("not seen just now"))
        // Left out since the first answer
        advance(Wait.MISSING_FOR.inWholeMilliseconds - MINUTE + 15_000)
        assertEquals("The bus is no longer seen", posted.single().title())
        advance(Wait.TELL_FOR.inWholeMilliseconds)
        assertLeft()
    }

    @Test
    fun noLongerSeenOneOfSeveral() {
        listed = { listOf(a47) }
        start(awaited(t31), awaited(a47))
        advance(Wait.MISSING_FOR.inWholeMilliseconds + Wait.TELL_FOR.inWholeMilliseconds + 2 * MINUTE)
        assertLeft("A47")
    }

    @Test
    fun aBusThatDropsOutAndComesBackStaysWaitedFor() {
        // Pushed out of the stop's five from the second to the fourth minute
        listed = { at -> if (at in NOW + MINUTE until NOW + 4 * MINUTE) listOf(a47) else listOf(t31, a47) }
        start(awaited(t31))
        advance(2 * MINUTE)
        assertTrue(notificationOf("T31").extras.getCharSequence(Notification.EXTRA_TEXT).toString().endsWith("not seen just now"))
        advance(3 * MINUTE)
        assertEquals("Plaza de Cayzedo A1", notificationOf("T31").extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        advance(3 * MINUTE)
        assertEquals(1, service.waitEngine.waits.size)
    }

    // 8. No data for a while

    @Test
    fun noData() {
        failing = true
        start(awaited(t31))
        assertEquals("T31 to Universidades, no data", posted.single().title())
        advance(Wait.NO_DATA_FOR.inWholeMilliseconds + 15_000)
        assertEquals("No data for a while", posted.single().title())
        advance(Wait.TELL_FOR.inWholeMilliseconds)
        assertLeft()
    }

    @Test
    fun noDataOneOfSeveral() {
        failing = true
        start(awaited(t31), a47Elsewhere)
        advance(Wait.NO_DATA_FOR.inWholeMilliseconds + Wait.TELL_FOR.inWholeMilliseconds + 2 * MINUTE)
        assertLeft("A47")
    }

    // 9. Notifications turned off

    @Test
    fun notificationsTurnedOff() {
        start(awaited(t31), awaited(a47))
        shadowOf(manager).setNotificationsEnabled(false)
        advance(WaitEngine.BEAT.inWholeMilliseconds)
        assertLeft()
    }

    @Test
    fun channelTurnedOff() {
        start(awaited(t31))
        manager.getNotificationChannel(WaitChannel.WAITS.id).importance = NotificationManager.IMPORTANCE_NONE
        advance(WaitEngine.BEAT.inWholeMilliseconds)
        assertLeft()
    }

    // 10. The process goes away

    @Test
    fun aNewProcessLeavesNoWaitNorNotification() {
        // What a process killed without warning left behind
        WaitNotifications.createChannels(app)
        for (id in WaitNotifications.ids) {
            manager.notify(id, Notification.Builder(app, WaitChannel.WAITS.id).setSmallIcon(android.R.drawable.ic_dialog_info).build())
        }
        WaitNotifications.cancelLeftovers(app)
        assertTrue(posted.isEmpty())
        // Nothing is stored: the desk of a new process knows no wait
        assertTrue(desk.running.value.isEmpty())
    }

    @Test
    fun tappingANotificationOpensItsStopInTheOneActivity() {
        start(awaited(t31))
        val intent = shadowOf(posted.single().contentIntent).savedIntent
        assertEquals(WaitNotifications.ACTION_OPEN_STOP, intent.action)
        assertEquals(com.yenreh.caligo.MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(WaitFixtures.STOP, intent.getStringExtra(WaitNotifications.EXTRA_STOP_ID))
        assertEquals(WaitFixtures.place.latitude, intent.getDoubleExtra(WaitNotifications.EXTRA_LATITUDE, 0.0), 0.0)
    }

    @Test
    fun eachNotificationGoesByItselfSoonAfterItsNextUpdateIsDue() {
        start(awaited(t31))
        val timeout = posted.single().timeoutAfter
        assertTrue(timeout > WaitEngine.BEAT.inWholeMilliseconds)
        assertTrue(timeout <= WaitEngine.BEAT.inWholeMilliseconds + WaitNotice.TIMEOUT_MARGIN_MS)
    }

    // Requests

    @Test
    fun waitsAtTheSameStopShareTheirRequests() {
        start(awaited(t31), awaited(a47))
        assertEquals(1, source.asked.size)
        // T31 ten minutes away: every minute
        advance(MINUTE)
        assertEquals(2, source.asked.size)
        advance(30_000)
        assertEquals(2, source.asked.size)
    }

    @Test
    fun anAnswerAScreenJustFetchedServesInsteadOfARequest() {
        // The card the bell was on, and the screen's next answer for the stop
        val card = FadingArrivals.EMPTY.fed(WaitFixtures.STOP, listOf(t31, a47), NOW - 20_000)
        source.recentAnswer = answer(NOW - 5_000, t31, a47)
        assertEquals(WaitStart.STARTED, desk.start(awaited(t31), card))
        controller = Robolectric.buildService(WaitService::class.java).create().startCommand(0, 1)
        scheduler.runCurrent()
        assertEquals(0, source.asked.size)
        // The next round goes by that answer's time: a minute after it
        advance(MINUTE - 10_000)
        assertEquals(0, source.asked.size)
        advance(10_000)
        assertEquals(1, source.asked.size)
    }

    @Test
    fun theAlertGoesToTheChannelTheSettingsAsk() {
        // As the settings' "Alerts" have it
        app.container.settings.setAlertSound(true)
        start(awaited(t31))
        assertEquals(WaitChannel.WAITS.id, posted.single().channelId)
        advance(t31.arrivalTime - 2 * MINUTE - now() + 1_000)
        assertEquals(WaitChannel.ALERT_SOUND.id, posted.single().channelId)
    }

    @Test
    fun pacedAsTheHomeByHowSoonItsBusComes() {
        assertEquals(60_000L, WaitEngine.pace(listOf(Wait.start(awaited(t31), NOW)), NOW))
        assertEquals(30_000L, WaitEngine.pace(listOf(Wait.start(awaited(t31), NOW)), t31.arrivalTime - 4 * MINUTE))
        // At the stop, as close as it gets
        assertEquals(30_000L, WaitEngine.pace(listOf(Wait.start(awaited(t31), NOW)), t31.arrivalTime + 30_000))
        val far = Wait.start(awaited(bus("T31", "t31", NOW + 30 * MINUTE)), NOW)
        assertEquals(120_000L, WaitEngine.pace(listOf(far), NOW))
    }

    @Test
    fun theCapIsFiveWaits() {
        val buses = (1..Wait.CAP).map { awaited(bus("T$it", "v$it", NOW + 30 * MINUTE)) }
        start(*buses.toTypedArray())
        assertEquals(WaitStart.CAP_REACHED, desk.start(awaited(bus("T9", "v9", NOW + 30 * MINUTE)), null))
        assertEquals(WaitStart.ALREADY_WAITING, desk.start(buses.first(), null))
        assertEquals(Wait.CAP, posted.size)
    }

    @Test
    fun channelsAreMadeOnceAndNeverAltered() {
        WaitNotifications.createChannels(app)
        // The user turns the sound alert down in the system's settings
        manager.getNotificationChannel(WaitChannel.ALERT_SOUND.id).importance = NotificationManager.IMPORTANCE_LOW
        WaitNotifications.createChannels(app)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(WaitChannel.ALERT_SOUND.id).importance)
        assertEquals(WaitChannel.entries.size, manager.notificationChannels.size)
    }
}

/**
 * Answers as a test scripts them: [answer] for each request, by the time
 * it is asked; throwing fails it. Every request is recorded in [asked].
 */
class FakeWaitSource(
    var answer: (point: AskPoint, now: Long) -> WaitAnswer,
    private val now: () -> Long,
) : WaitSource {
    val asked = mutableListOf<Pair<AskPoint, Long>>()

    /** What a screen fetched lately, served without a request */
    var recentAnswer: WaitAnswer? = null

    override suspend fun pointFor(bus: AwaitedBus): AskPoint {
        val place = bus.place
        return if (place != null && !bus.area) {
            AskPoint(place.latitude, place.longitude, placed = true)
        } else {
            AskPoint(bus.anchor.latitude, bus.anchor.longitude, placed = false)
        }
    }

    override fun recent(point: AskPoint, maxAge: Long): WaitAnswer? =
        recentAnswer?.takeIf { now() - it.fetchedAt <= maxAge }

    override suspend fun ask(point: AskPoint): WaitAnswer {
        val at = now()
        asked.add(point to at)
        return answer(point, at)
    }
}
