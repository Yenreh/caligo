package com.yenreh.caligo.waits

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.PhoneSamples
import com.yenreh.caligo.domain.waitFor
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
import org.junit.Assert.assertNull
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

    /** [bus] at another stop than the first, as another card names it */
    private fun atAnotherStop(bus: BusArrival) = awaited(bus, stopId = OTHER_STOP).copy(stopName = "Calle 5 con Kr 15", anchor = com.yenreh.caligo.domain.GeoPoint(3.44, -76.54))

    @Before
    fun setUp() {
        // The waits of 5.2.0, without trips, unless a test turns them on
        features.trips = false
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

    /** Only [lines] are left waited for, each in its stop's notification, and nothing else runs */
    private fun assertLeft(vararg lines: String) = assertLeftIn(lines.size, *lines)

    private fun assertLeftIn(notifications: Int, vararg lines: String) {
        // A line twice is a wait at each of two stops
        val titles = posted.map { it.title() }
        assertEquals(titles.toString(), notifications, titles.size)
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
        start(awaited(t31), atAnotherStop(a47))
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
        start(awaited(t31), atAnotherStop(a47))
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

    private val features get() = app.container.features

    /**
     * "I took it" of [line]'s notification. Without trips it ends the wait
     * there, as in 5.1; with them it opens the trip screen in the one
     * activity, with the ride, and the screen ends the wait.
     */
    private fun tookIt(line: String, stop: String = WaitFixtures.STOP, button: Int = 0) {
        val pending = notificationOf(line).actions[button].actionIntent
        val intent = shadowOf(pending).savedIntent
        assertEquals(WaitNotifications.ACTION_TOOK, intent.action)
        assertEquals(WaitNotifications.keyOf(intent)?.stopId, stop)
        if (!features.trips) {
            assertEquals(WaitActionReceiver::class.java.name, intent.component?.className)
            pending.send()
            shadowOf(Looper.getMainLooper()).idle()
            return
        }
        assertEquals(com.yenreh.caligo.MainActivity::class.java.name, intent.component?.className)
        val took = checkNotNull(WaitNotifications.tookOf(intent))
        assertEquals(line, took.line)
        desk.took(took.waitKey)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun tookIt() {
        start(awaited(t31))
        tookIt("T31")
        assertLeft()
    }

    @Test
    fun tookItOneOfSeveral() {
        start(awaited(t31), atAnotherStop(a47))
        tookIt("A47", stop = OTHER_STOP)
        assertLeft("T31")
    }

    @Test
    fun tookItWithTripsOpensTheTripScreenWhichEndsTheWait() {
        features.trips = true
        start(awaited(t31), atAnotherStop(a47))
        tookIt("A47", stop = OTHER_STOP)
        assertLeft("T31")
    }

    @Test
    fun withoutTripsITookItOpensNothing() {
        start(awaited(t31))
        assertFalse(shadowOf(notificationOf("T31").actions[0].actionIntent).isActivity)
        assertNull(com.yenreh.caligo.MainActivity.openedTrip(shadowOf(notificationOf("T31").actions[0].actionIntent).savedIntent, features.trips))
    }

    @Test
    fun tookItNamesTheRideForTheTripScreen() {
        features.trips = true
        start(awaited(t31))
        val intent = shadowOf(notificationOf("T31").actions[0].actionIntent).savedIntent
        val took = checkNotNull(WaitNotifications.tookOf(intent))
        assertEquals(com.yenreh.caligo.domain.TookRide("T31", "Universidades", WaitFixtures.STOP, WaitFixtures.STOP_NAME, "t31", t31.arrivalTime), took)
        // An activity, opened straight from the button: no trampoline
        assertTrue(shadowOf(notificationOf("T31").actions[0].actionIntent).isActivity)
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
    fun noToDidYouTakeItStopsWaiting() {
        e21Passes()
        start(awaited(e21))
        advance(e21.arrivalTime + MINUTE + 15_000 - now())
        val asking = notificationOf("E21")
        assertEquals(listOf("I took it", "No"), asking.actions.map { it.title.toString() })
        press("E21", 1)
        assertLeft()
    }

    @Test
    fun busPassesOneOfSeveral() {
        e21Passes()
        start(awaited(e21), awaited(t31))
        advance(e21.arrivalTime + 2 * MINUTE + 15_000 - now())
        assertLeft("T31")
    }

    // Waits at one stop: one notification

    private val group get() = posted.single()

    @Test
    fun waitsAtOneStopShareOneNotificationSoonestFirst() {
        start(awaited(t31), awaited(e21))
        assertEquals("E21 4${NBSP}min · T31 10${NBSP}min", group.title())
        assertEquals(WaitFixtures.STOP_NAME, group.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("E21: I took it", "T31: I took it", "Stop waiting"), group.actions.map { it.title.toString() })
        // The soonest's chip and bar, still a Live Update
        assertEquals("4${NBSP}min", NotificationCompat.getShortCriticalText(group))
        assertTrue(NotificationCompat.isRequestPromotedOngoing(group))
        assertEquals(2, desk.running.value.size)
    }

    @Test
    fun iTookItOnOneBusOfAStopEndsTheOthersThere() {
        start(awaited(t31), awaited(e21), atAnotherStop(a47))
        assertEquals(2, posted.size)
        // Ended as taken: the T31 at the same stop goes too, as no other ending takes it
        tookIt("E21")
        // The rider is gone from that stop; the other stop's wait goes on
        assertLeft("A47")
    }

    @Test
    fun withTripsITookItOnOneBusOfAStopEndsTheOthersThere() {
        features.trips = true
        start(awaited(t31), awaited(e21), atAnotherStop(a47))
        tookIt("E21")
        assertLeft("A47")
    }

    private fun group(line: String) = posted.single { line in it.title() }

    @Test
    fun stopWaitingOnAStopEndsEveryWaitThere() {
        start(awaited(t31), awaited(e21), atAnotherStop(a47))
        group("E21").actions.last().actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertLeft("A47")
    }

    @Test
    fun aStopsSwipeEndsEveryWaitThere() {
        start(awaited(t31), awaited(e21))
        val carrier = shadowOf(service).lastForegroundNotificationId
        val posts = service.posts
        group.deleteIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        // Never shown again on the way out
        assertEquals(posts, service.posts)
        assertLeft()
        assertTrue(carrier in WaitNotifications.ids)
    }

    @Test
    fun theCarrierMovesToAnotherStopAsItsGroupEnds() {
        start(awaited(t31), awaited(e21), atAnotherStop(a47))
        val carrier = shadowOf(service).lastForegroundNotificationId
        group("E21").actions.last().actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(carrier, shadowOf(service).lastForegroundNotificationId)
        assertLeft("A47")
    }

    // The orphan matrix for groups: one stop with two waits, and two such stops

    @Test
    fun theBellOnOneWaitOfAStopLeavesTheOtherAsAWaitAlone() {
        start(awaited(t31), awaited(e21))
        desk.stop(awaited(e21).key)
        assertLeft("T31")
        assertEquals("T31 to Universidades, 10${NBSP}min", group.title())
        assertEquals(listOf("I took it", "Stop waiting"), group.actions.map { it.title.toString() })
    }

    @Test
    fun twoStopsOfTwoWaitsEndOneStopAtATime() {
        start(awaited(t31), awaited(e21), atAnotherStop(a47), atAnotherStop(t31))
        assertLeftIn(2, "T31", "E21", "A47", "T31")
        assertEquals(4, desk.running.value.size)
        group("E21").actions.last().actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertLeftIn(1, "A47", "T31")
        group("A47").deleteIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertLeft()
    }

    @Test
    fun twoStopsOfTwoWaitsEndAsTheirBusesPass() {
        e21Passes()
        // The other stop answered as itself, near the first
        otherAnswer = { at -> answer(at, *listed(at).toTypedArray(), stopId = OTHER_STOP) }
        fun other(bus: BusArrival) = atAnotherStop(bus).copy(place = null)
        start(awaited(t31), awaited(e21), other(t31), other(e21))
        // Each E21 passes, unanswered; each stop goes on with its T31
        advance(e21.arrivalTime + 2 * MINUTE + 15_000 - now())
        assertLeft("T31", "T31")
    }

    @Test
    fun twoStopsOfTwoWaitsEndAsDataStops() {
        failing = true
        start(awaited(t31), awaited(e21), atAnotherStop(a47), atAnotherStop(t31))
        advance(Wait.NO_DATA_FOR.inWholeMilliseconds + Wait.TELL_FOR.inWholeMilliseconds + 2 * MINUTE)
        assertLeft()
    }

    @Test
    fun twoStopsOfTwoWaitsEndWhenNotificationsAreTurnedOff() {
        start(awaited(t31), awaited(e21), atAnotherStop(a47), atAnotherStop(t31))
        shadowOf(manager).setNotificationsEnabled(false)
        advance(WaitEngine.BEAT.inWholeMilliseconds)
        assertLeft()
    }

    @Test
    fun twoStopsOfTwoWaitsEndAtTheTimeLimit() {
        listed = { listOf(far, farther) }
        start(awaited(far), awaited(farther), atAnotherStop(far), atAnotherStop(farther))
        advance(Wait.TIME_LIMIT.inWholeMilliseconds + 15_000)
        assertLeft()
    }

    @Test
    fun aBusThatPassesAsksInsideItsStopsNotificationThenTheGroupComesBack() {
        e21Passes()
        start(awaited(t31), awaited(e21))
        advance(e21.arrivalTime + MINUTE + 15_000 - now())
        assertEquals("Did you take the E21?", group.title())
        assertEquals(listOf("Yes", "No", "Stop waiting"), group.actions.map { it.title.toString() })
        // No: that bus no longer waited for; the T31 alone, as one wait shows
        group.actions[1].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        advance(1)
        assertLeft("T31")
        assertTrue(group.title(), group.title().startsWith("T31 to Universidades, "))
    }

    @Test
    fun yesOnTheGroupsAskIsIToolItForThatBus() {
        e21Passes()
        start(awaited(t31), awaited(e21))
        advance(e21.arrivalTime + MINUTE + 15_000 - now())
        assertEquals("Did you take the E21?", group.title())
        tookIt("E21")
        assertLeft()
    }

    @Test
    fun unansweredTheGroupComesBackAfterTheMinute() {
        e21Passes()
        start(awaited(t31), awaited(e21))
        advance(e21.arrivalTime + 2 * MINUTE + 15_000 - now())
        assertLeft("T31")
        assertTrue(group.title(), group.title().startsWith("T31 to Universidades"))
    }

    @Test
    fun eachBusOfAStopAlertsOnceOnTheAlertChannel() {
        listed = { listOf(e21, t31) }
        start(awaited(t31), awaited(e21))
        assertEquals(WaitChannel.WAITS.id, group.channelId)
        advance(e21.arrivalTime - 2 * MINUTE - now() + 1_000)
        // The E21's alert has gone out, on the alert channel, silent by default
        assertEquals(WaitChannel.ALERT_SILENT.id, group.channelId)
        assertTrue(service.waitEngine.waits.single { it.last.line == "E21" }.alerted)
        assertFalse(service.waitEngine.waits.single { it.last.line == "T31" }.alerted)
        advance(WaitEngine.BEAT.inWholeMilliseconds)
        assertTrue("once", group.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // The T31's own alert, in its turn
        advance(t31.arrivalTime - 2 * MINUTE - now() + 1_000)
        assertTrue(service.waitEngine.waits.single { it.last.line == "T31" }.alerted)
    }

    @Test
    fun theSameContentIsNotPostedAgainForAWhileYetNeverTimesOut() {
        // A bus far off, every answer the same
        start(awaited(a47), awaited(bus("A48", "a48", NOW + 30 * MINUTE)))
        var shown = group
        var postedAt = now()
        val posts = service.posts
        val end = now() + 10 * MINUTE
        while (now() < end) {
            advance(1_000)
            val current = group
            if (current !== shown) {
                assertTrue(now() - postedAt < shown.timeoutAfter)
                shown = current
                postedAt = now()
            }
            assertTrue(now() - postedAt < shown.timeoutAfter)
        }
        // As the minutes and the bar move together, and again after 20 s: not one at every beat, 40 in 10 min
        assertTrue("${service.posts - posts} posts", service.posts - posts <= 22)
        assertTrue(service.holdsWakeLock)
    }

    // A vehicle's next trip, as the phone sampled it at stop 514208

    /** The phone's answers, moved to start at [NOW] */
    private val offset = NOW - PhoneSamples.answers.first().fetchedAt

    private fun sampledAt(at: Long): WaitAnswer {
        val answer = PhoneSamples.answers.lastOrNull { it.fetchedAt + offset <= at } ?: PhoneSamples.answers.first()
        val buses = answer.buses.map { it.copy(arrivalTime = it.arrivalTime + offset) }
        return WaitAnswer(listOf(com.yenreh.caligo.domain.entities.NearbyStop(PhoneSamples.STOP, PhoneSamples.STOP_NAME, 30.0, buses)), at)
    }

    private fun sampled(clock: String) = PhoneSamples.time(clock) + offset

    @Test
    fun aWaitEndsWithItsTripAndNeverCountsDownToTheNextOne() {
        source.answer = { _, at -> sampledAt(at) }
        val a47 = sampledAt(NOW).stops.single().arrivals.first { it.vehicleId == "261001" }
        val bus = AwaitedBus(PhoneSamples.STOP, "Calimio-Sanin", a47, WaitFixtures.anchor, place = WaitFixtures.place)
        start(bus)
        val titles = mutableListOf<String>()
        while (posted.isNotEmpty() && now() < sampled("14:52:00")) {
            titles.add(posted.single().title())
            advance(15_000)
        }
        // Counted down, at the stop as its next trip showed, asked, then gone
        assertTrue(titles.first(), titles.first().startsWith("A47 to Valle Grande, 6${NBSP}min"))
        assertTrue(titles.toString(), "A47 to Valle Grande, at the stop" in titles)
        assertTrue(titles.toString(), "Did you take the A47?" in titles)
        assertTrue(titles.toString(), titles.none { Regex("A47 to Valle Grande, (4[0-9]|1[0-9]|[2-3][0-9])${NBSP}min").matches(it) })
        assertLeft()
    }

    @Test
    fun theNextTripIsANewWaitWhileTheTripBeforeIsAtTheStop() {
        source.answer = { _, at -> sampledAt(at) }
        val first = sampledAt(NOW).stops.single().arrivals.first { it.vehicleId == "261001" }
        start(AwaitedBus(PhoneSamples.STOP, "Calimio-Sanin", first, WaitFixtures.anchor, place = WaitFixtures.place))
        // Asked every 30 s: the answer of 14:46:39 is in by 14:47:00
        advance(sampled("14:47:00") - now())
        assertTrue(posted.single().title(), posted.single().title().endsWith("at the stop"))
        assertEquals("Calimio-Sanin", posted.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // The bell of the next trip's row is empty, and starts a wait of its own
        val next = sampledAt(now()).stops.single().arrivals.first { it.vehicleId == "261001" }
        assertEquals(null, desk.running.value.waitFor(PhoneSamples.STOP, next))
        assertEquals(awaitedKeyOf(first), desk.running.value.waitFor(PhoneSamples.STOP, first.copy(arrivalTime = sampled("14:46:39"))))
        assertEquals(WaitStart.STARTED, desk.start(AwaitedBus(PhoneSamples.STOP, "Calimio-Sanin", next, WaitFixtures.anchor, place = WaitFixtures.place), null))
        advance(1)
        // One stop: one notification, the bus at the stop first
        assertEquals(listOf("A47 at the stop · A47 46${NBSP}min"), posted.map { it.title() })
    }

    private fun awaitedKeyOf(bus: BusArrival) = com.yenreh.caligo.domain.WaitKey(PhoneSamples.STOP, bus.vehicleId, bus.arrivalTime)

    // 6. The time limit

    /** Due within the limit, and still listed long after: only the limit ends their waits */
    private val far = bus("T31", "t31", NOW + 30 * MINUTE)
    private val farther = bus("A47", "a47", NOW + 32 * MINUTE, destination = "Valle Grande")

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

    @Test
    fun aBusListedFarAheadIsWaitedForPastTheLimit() {
        val late = bus("T31", "t31", NOW + 48 * MINUTE)
        listed = { listOf(late) }
        start(awaited(late))
        advance(Wait.TIME_LIMIT.inWholeMilliseconds + MINUTE)
        assertEquals("T31 to Universidades, 2${NBSP}min", posted.single().title())
        // Ten minutes past the time it gave at the start
        advance(late.arrivalTime + Wait.PAST_DUE.inWholeMilliseconds - now() - 15_000)
        assertEquals(1, posted.size)
        advance(30_000)
        assertLeft()
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
    fun failingRequestsWaitLongerEachTimeYetTheWaitEndsAt5Minutes() {
        failing = true
        start(awaited(t31))
        advance(Wait.NO_DATA_FOR.inWholeMilliseconds - 1_000)
        // T31 ten minutes away, every minute: 30 s more, then 60, then 120
        assertEquals(listOf(0L, 90_000L, 210_000L), source.asked.map { it.second - NOW })
        assertEquals("T31 to Universidades, no data", posted.single().title())
        advance(16_000)
        assertEquals("No data for a while", posted.single().title())
    }

    @Test
    fun anAnswerAfterFailuresGoesBackToTheUsualPace() {
        failing = true
        start(awaited(t31))
        advance(100_000)
        failing = false
        // Answered at 3:30, two failures on: then every minute again
        advance(3 * MINUTE)
        assertEquals(listOf(0L, 90_000L, 210_000L, 270_000L), source.asked.map { it.second - NOW })
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

    @Test
    fun theAlertChannelTurnedOffOnlySilencesTheAlert() {
        // The user turned the silent alert off in the system, say to stop its peek
        WaitNotifications.createChannels(app)
        manager.getNotificationChannel(WaitChannel.ALERT_SILENT.id).importance = NotificationManager.IMPORTANCE_NONE
        assertTrue(AndroidNotificationAccess(app).enabled())
        start(awaited(t31))
        advance(t31.arrivalTime - 2 * MINUTE - now() + 1_000)
        // No ending, no alert, every post on the channel of updates
        val shown = posted.single()
        assertEquals(WaitChannel.WAITS.id, shown.channelId)
        assertTrue(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1, service.waitEngine.waits.size)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aWaitAlertedMovesBackWhenItsAlertChannelIsTurnedOff() {
        start(awaited(t31))
        advance(t31.arrivalTime - 2 * MINUTE - now() + 1_000)
        assertEquals(WaitChannel.ALERT_SILENT.id, posted.single().channelId)
        manager.getNotificationChannel(WaitChannel.ALERT_SILENT.id).importance = NotificationManager.IMPORTANCE_NONE
        advance(WaitEngine.BEAT.inWholeMilliseconds)
        assertEquals(WaitChannel.WAITS.id, posted.single().channelId)
        assertEquals(1, service.waitEngine.waits.size)
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
        // Its details are titled as the service names the stop
        assertEquals(WaitFixtures.STOP_NAME, intent.getStringExtra(WaitNotifications.EXTRA_STOP_NAME))
    }

    @Test
    fun eachWaitIsALiveUpdateWithTheMinutesAsItsChip() {
        start(awaited(t31), awaited(a47))
        for (notification in posted) {
            assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
            // Ongoing, titled, progress style, not colorized, no group summary: promotable
            assertTrue(notification.hasPromotableCharacteristics())
            assertEquals(null, notification.group)
        }
        assertEquals("10${NBSP}min", NotificationCompat.getShortCriticalText(notificationOf("T31")))
        // Older versions count down with a chronometer to the bus
        val t31Shown = notificationOf("T31")
        assertTrue(NotificationCompat.getUsesChronometer(t31Shown))
        assertEquals(t31.arrivalTime, t31Shown.`when`)
    }

    @Test
    fun eachNotificationGoesByItselfSoonAfterItsNextUpdateIsDue() {
        start(awaited(t31))
        val timeout = posted.single().timeoutAfter
        // Past the latest post to come: the same left out for 20 s, then the next beat
        assertEquals(WaitNotice.REPOST_AFTER_MS + WaitEngine.BEAT.inWholeMilliseconds + WaitNotice.TIMEOUT_MARGIN_MS, timeout)
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
    fun waitsAtTwoPlatformsOfAStationShareOneRequestAndAFarStopAsksItsOwn() {
        // B2 of the same station, 110 m north of A1; another stop 2 km off
        val platform = com.yenreh.caligo.domain.GeoPoint(WaitFixtures.place.latitude + 0.001, WaitFixtures.place.longitude)
        val far = com.yenreh.caligo.domain.GeoPoint(WaitFixtures.place.latitude + 0.018, WaitFixtures.place.longitude)
        val e21AtB2 = awaited(e21, stopId = "500801").copy(place = platform)
        val a47Far = awaited(a47, stopId = "500900").copy(place = far)
        source.answer = { point, at ->
            val near = com.yenreh.caligo.domain.metersBetween(point.latitude, point.longitude, far.latitude, far.longitude) > 1_000
            WaitAnswer(
                if (near) {
                    listOf(
                        com.yenreh.caligo.domain.entities.NearbyStop(WaitFixtures.STOP, WaitFixtures.STOP_NAME, 40.0, listOf(t31)),
                        com.yenreh.caligo.domain.entities.NearbyStop("500801", "Plaza de Cayzedo B2", 90.0, listOf(e21)),
                    )
                } else {
                    listOf(com.yenreh.caligo.domain.entities.NearbyStop("500900", "Far", 20.0, listOf(a47)))
                },
                at,
            )
        }
        start(awaited(t31), e21AtB2, a47Far)
        // One request for the station, one for the far stop
        assertEquals(2, source.asked.size)
        assertEquals(setOf(WaitFixtures.place.latitude, far.latitude), source.asked.map { it.first.latitude }.toSet())
        // E21 four minutes away paces the station: every 30 s, the one request still serving both
        advance(2 * MINUTE)
        val station = source.asked.count { it.first.latitude != far.latitude }
        assertEquals(5, station)
        assertTrue(posted.none { "no data" in it.title() })
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
        val alert = posted.single()
        assertEquals(WaitChannel.ALERT_SOUND.id, alert.channelId)
        assertTrue(alert.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        // Every later post stays on that channel, silent
        advance(WaitEngine.BEAT.inWholeMilliseconds)
        assertEquals(WaitChannel.ALERT_SOUND.id, posted.single().channelId)
        assertTrue(posted.single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun aWaitStartedNearItsBusAlertsOnce() {
        // Posted as the service starts, then again at the engine's first beat
        start(awaited(e21.copy(arrivalTime = NOW + MINUTE)))
        assertEquals(WaitChannel.ALERT_SILENT.id, posted.single().channelId)
        assertTrue(posted.single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(service.waitEngine.waits.single().alerted)
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
        // One stop: one notification for the five
        assertEquals(1, posted.size)
        assertEquals(Wait.CAP, service.waitEngine.waits.size)
    }

    @Test
    fun theWaitsChannelShowsOnTheLockScreenYetNeverMakesANoise() {
        WaitNotifications.createChannels(app)
        // Default, as a lock screen that hides silent notifications still shows it
        val channel = manager.getNotificationChannel(WaitChannel.WAITS.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(null, channel.sound)
        assertFalse(channel.shouldVibrate())
        // The alerts keep theirs
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(WaitChannel.ALERT_SILENT.id).importance)
        start(awaited(t31))
        val shown = posted.single()
        assertEquals(WaitChannel.WAITS.id, shown.channelId)
        assertTrue(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun theLowWaitsChannelOf51Goes() {
        manager.createNotificationChannel(NotificationChannel("waits", "Awaited buses", NotificationManager.IMPORTANCE_LOW))
        WaitNotifications.createChannels(app)
        assertEquals(null, manager.getNotificationChannel("waits"))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(WaitChannel.WAITS.id).importance)
        assertEquals(WaitChannel.entries.size, manager.notificationChannels.size)
    }

    @Test
    fun aWaitsChannelTurnedOffIn51StaysOff() {
        manager.createNotificationChannel(NotificationChannel("waits", "Awaited buses", NotificationManager.IMPORTANCE_NONE))
        // The bell asks before any channel of 5.2 is made: the old one answers
        assertFalse(AndroidNotificationAccess(app).enabled())
        WaitNotifications.createChannels(app)
        assertEquals(null, manager.getNotificationChannel("waits"))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(WaitChannel.WAITS.id).importance)
        assertFalse(AndroidNotificationAccess(app).enabled())
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
