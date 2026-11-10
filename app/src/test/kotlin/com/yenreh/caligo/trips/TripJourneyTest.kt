package com.yenreh.caligo.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.a47
import com.yenreh.caligo.trips.JourneyFixtures.a47Route
import com.yenreh.caligo.trips.JourneyFixtures.between
import com.yenreh.caligo.trips.JourneyFixtures.busOn
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.firstArrivals
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.JourneyFixtures.origin
import com.yenreh.caligo.trips.JourneyFixtures.secondArrivals
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.out
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A planner trip on virtual time, with scripted fixes, buses and arrivals
 * and the real waits: the walk to the stop, the waits for each line that
 * makes the first ride, "I took it" on one, the change on foot, the next
 * ride's wait, its bus taken by position, the walk to the end; and the
 * ways such a trip ends, with nothing of it left, its waits included.
 */
@RunWith(AndroidJUnit4::class)
class TripJourneyTest {
    private val h = TripHarness()
    private val stop = GeoPoint(out[0].latitude, out[0].longitude)
    private val change = GeoPoint(a47[0].latitude, a47[0].longitude)

    @Before
    fun setUp() {
        h.source.arrivalsAnswer = { stops, at -> if (stops.any { it.stopId == out[0].stopId }) firstArrivals(at) else secondArrivals(at) }
        // The waits' own answers list the buses as the arrivals did
        h.waitsAnswering { _, at ->
            WaitAnswer(firstArrivals(NOW).stops + secondArrivals(NOW + 12 * MINUTE).stops, at)
        }
    }

    @After
    fun tearDown() = h.tearDown()

    private fun startJourney() {
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
    }

    /** Walking from the origin at 1.2 m/s */
    private fun walking(at: Long) = between(origin, stop, (at - NOW) * 1.2 / 1000)

    private val waits get() = h.app.container.waits

    /** "I took it" on [line]'s wait, as the trip screen it opens does */
    private fun tookIt(line: String) {
        val key = waits.running.value.map { it.key }.first { k -> h.waitTitles.isNotEmpty() && k.vehicleId == if (line == "E21") "637001" else "815001" }
        val took = TookRide(line, "Universidades", key.stopId, out[0].name, key.vehicleId, key.trip)
        assertTrue(h.desk.took(took))
        waits.took(key)
        h.advance(1)
    }

    @Test
    fun aWholeTripWithItsWalksRidesAndChange() {
        startJourney()
        h.assertRunning()
        // The walk, and a wait for each line of the first ride
        h.move(MINUTE) { walking(it) }
        with(h) { assertTrue(checkNotNull(shown).title(), checkNotNull(shown).title().startsWith("Walk to Plaza de Cayzedo A1, ")) }
        assertEquals(setOf("E21", "T31"), h.waitTitles.map { it.substringBefore(" ") }.toSet())
        // At the stop
        h.move(4 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        assertEquals("Take the E21 or T31 at Plaza de Cayzedo A1", with(h) { checkNotNull(shown).title() })
        // The T31 comes first: its wait ends as taken, the E21's without asking
        tookIt("T31")
        assertTrue(h.waitTitles.toString(), h.waitTitles.isEmpty())
        val t31At = h.now()
        h.source.byLine = { line, at -> if (line == "T31") listOf(busOn(calle5Route, "41022", (at - t31At) * 7.0 / 1000)) else emptyList() }
        h.move(7 * MINUTE) { at ->
            if (h.desk.running.value?.ride != null) calle5Route.pointAt((at - t31At) * 7.0 / 1000) else null
        }
        // Off at the change: on foot to the A47, whose wait starts
        val onFoot = checkNotNull(h.desk.running.value)
        assertEquals(2, onFoot.index)
        assertTrue(h.waitTitles.toString(), h.waitTitles.any { it.startsWith("A47") })
        assertTrue(h.waitTitles.none { it.startsWith("T31") || it.startsWith("E21") })
        // The T31 ride taught the planner how it ran
        assertTrue(h.source.observed.toString(), h.source.observed.any { "T31" in it })
        // The change on foot, then away on the A47 beside its bus: taken by position
        val walkAt = h.now()
        h.move(3 * MINUTE) { at -> between(out[6].let { GeoPoint(it.latitude, it.longitude) }, change, (at - walkAt) * 1.2 / 1000) }
        assertTrue(h.desk.running.value?.phase(h.now()) is JourneyPhase.Waiting)
        val leftAt = h.now()
        h.source.byLine = { line, at -> if (line == "A47") listOf(busOn(a47Route, "22074", 20 + (at - leftAt) * 8.0 / 1000)) else emptyList() }
        h.move(MINUTE) { at -> a47Route.pointAt((at - leftAt) * 8.0 / 1000) }
        assertEquals("A47", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.waitTitles.toString(), h.waitTitles.isEmpty())
        // To the A47's last stop, then the walk to the end
        h.move(4 * MINUTE) { at -> if (h.desk.running.value?.ride != null) a47Route.pointAt((at - leftAt) * 8.0 / 1000) else null }
        assertEquals(4, h.desk.running.value?.index)
        with(h) { assertTrue(checkNotNull(shown).title(), checkNotNull(shown).title().startsWith("Walk to Unicentro, ")) }
        h.move(5 * MINUTE) { JourneyFixtures.destination }
        h.assertEnded(TripEnding.REACHED)
        assertTrue(h.waitTitles.isEmpty())
        // Every wait ran in the trip's own service: the waits' was never asked to start
        assertEquals(0, h.waitServiceStarts)
    }

    @Test
    fun theNextRidesWaitsStartInTheTripServiceWithNoScreenOnShow() {
        startJourney()
        h.move(5 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        tookIt("T31")
        val t31At = h.now()
        // The screen off and the app away: no activity anywhere, only the trip's foreground service
        h.move(7 * MINUTE) { at -> if (h.desk.running.value?.ride != null) calle5Route.pointAt((at - t31At) * 7.0 / 1000) else null }
        assertEquals(2, h.desk.running.value?.index)
        val a47Waits = h.tripWaitNotifications
        assertEquals(1, a47Waits.size)
        with(h) { assertTrue(a47Waits.single().title(), a47Waits.single().title().startsWith("A47 to Valle Grande")) }
        assertEquals(0, h.waitServiceStarts)
        // Shown as waited for, on the rows' bells too
        assertTrue(waits.running.value.any { it.key.vehicleId == "261001" })
        // As any wait's: "I took it" opens the trip screen, and the notification goes by itself if the process dies
        val shown = a47Waits.single()
        assertEquals(com.yenreh.caligo.waits.WaitNotifications.ACTION_TOOK, shadowOf(shown.actions[0].actionIntent).savedIntent.action)
        assertTrue(shown.timeoutAfter in 1..(TripEngine.BEAT.inWholeMilliseconds + com.yenreh.caligo.waits.WaitNotice.TIMEOUT_MARGIN_MS))
        h.desk.finish()
        h.advance(1)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun stopWaitingOnATripsWaitEndsItAndItIsNotAskedForAgain() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, h.tripWaitNotifications.size)
        val asked = h.source.arrivalsAsked.size
        for (n in h.tripWaitNotifications) {
            n.actions[1].actionIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        h.advance(1)
        assertTrue(h.tripWaitNotifications.isEmpty())
        h.move(3 * MINUTE) { walking(it) }
        assertEquals(asked, h.source.arrivalsAsked.size)
        assertTrue(h.tripWaitNotifications.isEmpty())
        h.assertRunning()
    }

    @Test
    fun aTripsWaitSwipedOrItsBellTappedEndsIt() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        val first = h.tripWaitNotifications.first()
        first.deleteIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        h.advance(1)
        assertEquals(1, h.tripWaitNotifications.size)
        // The filled bell on its row
        waits.stop(waits.running.value.single().key)
        h.advance(1)
        assertTrue(h.tripWaitNotifications.isEmpty())
        assertTrue(waits.running.value.isEmpty())
    }

    @Test
    fun theRidersOwnWaitsKeepTheirCapApartFromATripsWaits() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, waits.running.value.size)
        // Five of the rider's own still start, beside the trip's two
        for (i in 1..com.yenreh.caligo.domain.Wait.CAP) {
            val bus = com.yenreh.caligo.waits.WaitFixtures.bus("X$i", "x$i", NOW + 30 * MINUTE)
            assertEquals(com.yenreh.caligo.waits.WaitStart.STARTED, waits.start(com.yenreh.caligo.waits.WaitFixtures.awaited(bus), null))
        }
        assertEquals(com.yenreh.caligo.waits.WaitStart.CAP_REACHED, waits.start(com.yenreh.caligo.waits.WaitFixtures.awaited(com.yenreh.caligo.waits.WaitFixtures.bus("X9", "x9", NOW + 30 * MINUTE)), null))
        // A bus the trip waits for already has its wait
        val tripKey = waits.running.value.first { it.key.vehicleId == "637001" }.key
        val e21 = firstArrivals(NOW).stops.single().arrivals.first { it.vehicleId == tripKey.vehicleId }
        assertEquals(com.yenreh.caligo.waits.WaitStart.ALREADY_WAITING, waits.start(com.yenreh.caligo.domain.AwaitedBus(out[0].stopId, out[0].name, e21, stop), null))
    }

    @Test
    fun theOtherWaitOfARideTakenEndsWithoutAsking() {
        startJourney()
        h.move(5 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        assertEquals(2, h.waitTitles.size)
        tookIt("E21")
        assertTrue(h.waitTitles.toString(), h.waitTitles.isEmpty())
        assertTrue(waits.running.value.isEmpty())
        assertEquals("E21", h.desk.running.value?.ride?.plan?.line)
    }

    @Test
    fun aBusTakenByPositionEndsEveryWaitOfItsRide() {
        startJourney()
        h.move(5 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        val leftAt = h.now()
        h.source.byLine = { line, at -> if (line == "E21") listOf(busOn(calle5Route, "11002", 20 + (at - leftAt) * 8.0 / 1000)) else emptyList() }
        h.move(MINUTE) { at -> calle5Route.pointAt((at - leftAt) * 8.0 / 1000) }
        assertEquals("E21", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.waitTitles.toString(), h.waitTitles.isEmpty())
    }

    @Test
    fun finishMidWalkEndsTheTripAndItsWaits() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, h.waitTitles.size)
        h.desk.finish()
        h.advance(1)
        assertTrue(h.waitTitles.toString(), h.waitTitles.isEmpty())
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun finishOnTheNotificationWhileWaitingAtTheStop() {
        startJourney()
        h.move(5 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        h.press(0)
        h.advance(1)
        assertTrue(h.waitTitles.isEmpty())
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun swipedAwayMidRide() {
        startJourney()
        h.move(5 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        tookIt("T31")
        h.swipe()
        h.advance(1)
        assertTrue(h.waitTitles.isEmpty())
        h.assertEnded(TripEnding.DISMISSED)
    }

    @Test
    fun notificationsTurnedOffMidWalk() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        shadowOf(h.manager).setNotificationsEnabled(false)
        h.advance(TripEngine.BEAT.inWholeMilliseconds)
        assertTrue(h.waitTitles.isEmpty())
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun locationOffWhileWaiting() {
        startJourney()
        h.move(5 * MINUTE) { at -> if (at < NOW + 4 * MINUTE) walking(at) else stop }
        h.location.lose()
        h.advance(1)
        assertTrue(h.waitTitles.isEmpty())
        h.assertEnded(TripEnding.NO_LOCATION)
    }

    @Test
    fun threeHoursEndAWholeTrip() {
        startJourney()
        h.move(com.yenreh.caligo.domain.Journey.TIME_LIMIT.inWholeMilliseconds + MINUTE) { stop }
        h.assertEnded(TripEnding.TIME_LIMIT)
        assertTrue(h.waitTitles.isEmpty())
    }

    @Test
    fun waitsAllGoneWithNoneTakenAreAskedForAgain() {
        // The buses drop out of the waits' answers: no longer seen, the waits end by themselves
        var listed = true
        h.waitsAnswering { _, at -> WaitAnswer(if (listed) firstArrivals(NOW).stops else emptyList(), at) }
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, h.tripWaitNotifications.size)
        val asked = h.source.arrivalsAsked.size
        listed = false
        h.move(9 * MINUTE) { stop }
        assertTrue(h.source.arrivalsAsked.size > asked)
        assertEquals(2, h.tripWaitNotifications.size)
    }

    @Test
    fun theWalkAsksForNoBusesUntilNearTheStop() {
        startJourney()
        // 300 m out, within the 300 m: asked once near
        h.move(30_000) { origin }
        assertTrue(h.source.askedLines.toString(), h.source.askedLines.isEmpty() || h.source.askedLines.all { it in setOf("E21", "T31") })
    }
}
