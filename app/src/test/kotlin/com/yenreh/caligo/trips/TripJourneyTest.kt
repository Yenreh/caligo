package com.yenreh.caligo.trips

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.Wait
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
import com.yenreh.caligo.waits.WaitChannel
import com.yenreh.caligo.waits.WaitFixtures
import com.yenreh.caligo.waits.WaitStart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A planner trip on virtual time, with scripted fixes, buses and arrivals:
 * the walk to the stop, the buses awaited for each line that makes the
 * first ride, "I took the T31" on the trip's notification, the change on
 * foot, the next ride's bus awaited, taken by position, the walk to the
 * end; one notification of the trip's own all along, the rider's own
 * waits apart; and the ways such a trip ends, with nothing of it left.
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
        h.waitsAnswering { _, at -> WaitAnswer(firstArrivals(NOW).stops + secondArrivals(NOW + 12 * MINUTE).stops, at) }
    }

    @After
    fun tearDown() = h.tearDown()

    private fun startJourney() {
        h.desk.startJourney(option, DESTINATION)
        h.start(null)
    }

    /** Walking from the origin at 1.2 m/s */
    private fun walking(at: Long) = between(origin, stop, (at - NOW) * 1.2 / 1000)

    /** On foot until 4 min, at the stop from then */
    private fun toTheStop(at: Long) = if (at < NOW + 4 * MINUTE) walking(at) else stop

    private val waits get() = h.app.container.waits

    private fun Notification.title() = with(h) { title() }

    private fun Notification.text() = with(h) { text() }

    private val shown get() = checkNotNull(h.shown)

    /** Never more than the trip's own notification, besides the rider's own waits */
    private fun assertOne() = assertEquals(h.tripOwn.map { it.title() }.toString(), 1, h.tripOwn.size)

    @Test
    fun aWholeTripWithItsWalksRidesAndChange() {
        startJourney()
        h.assertRunning()
        // The walk, with the buses of each line awaited, soonest first, in the trip's one notification
        h.move(MINUTE) { walking(it) }
        assertOne()
        assertEquals(listOf("E21", "T31"), h.awaitedLines)
        assertTrue(shown.title(), shown.title().startsWith("Walk to the stop, "))
        assertEquals("Plaza de Cayzedo A1 · E21 7${NBSP}min · T31 9${NBSP}min", shown.text())
        // At the stop
        h.move(4 * MINUTE) { toTheStop(it) }
        assertOne()
        assertEquals("Take the E21 or T31", shown.title())
        assertEquals("At Plaza de Cayzedo A1 · E21 3${NBSP}min · T31 5${NBSP}min", shown.text())
        assertEquals("3${NBSP}min", androidx.core.app.NotificationCompat.getShortCriticalText(shown))
        assertEquals(listOf("I took the E21", "I took the T31", "Finish"), shown.actions.map { it.title.toString() })
        // The T31 taken: every wait of the ride ends
        h.pressTitled("I took the T31")
        h.advance(1)
        assertEquals("T31", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.awaitedLines.toString(), h.awaitedLines.isEmpty())
        val t31At = h.now()
        h.source.byLine = { line, at -> if (line == "T31") listOf(busOn(calle5Route, "41022", (at - t31At) * 7.0 / 1000)) else emptyList() }
        h.move(7 * MINUTE) { at -> if (h.desk.running.value?.ride != null) calle5Route.pointAt((at - t31At) * 7.0 / 1000) else null }
        // Off at the change: on foot to the A47, whose bus is awaited
        assertEquals(2, h.desk.running.value?.index)
        assertEquals(listOf("A47"), h.awaitedLines)
        assertOne()
        assertTrue(shown.text(), shown.text().startsWith("Kr 22 con Cl 10 · A47 "))
        // The T31 ride taught the planner how it ran
        assertTrue(h.source.observed.toString(), h.source.observed.any { "T31" in it })
        // The change on foot, then away on the A47 beside its bus: taken by position
        val walkAt = h.now()
        h.move(3 * MINUTE) { at -> between(GeoPoint(out[6].latitude, out[6].longitude), change, (at - walkAt) * 1.2 / 1000) }
        assertTrue(h.desk.running.value?.phase(h.now()) is JourneyPhase.Waiting)
        val leftAt = h.now()
        h.source.byLine = { line, at -> if (line == "A47") listOf(busOn(a47Route, "22074", 20 + (at - leftAt) * 8.0 / 1000)) else emptyList() }
        h.move(MINUTE) { at -> a47Route.pointAt((at - leftAt) * 8.0 / 1000) }
        assertEquals("A47", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.awaitedLines.isEmpty())
        // To the A47's last stop, then the walk to the end
        h.move(4 * MINUTE) { at -> if (h.desk.running.value?.ride != null) a47Route.pointAt((at - leftAt) * 8.0 / 1000) else null }
        assertEquals(4, h.desk.running.value?.index)
        assertTrue(shown.title(), shown.title().startsWith("Walk to your destination, "))
        assertEquals("Unicentro", shown.text())
        h.move(5 * MINUTE) { JourneyFixtures.destination }
        h.assertEnded(TripEnding.REACHED)
        // Nothing of the waits' own service was ever needed
        assertEquals(0, h.waitServiceStarts)
    }

    @Test
    fun theNextRidesBusIsAwaitedInTheTripServiceWithNoScreenOnShow() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("I took the T31")
        val t31At = h.now()
        // The screen off and the app away: no activity anywhere, only the trip's foreground service
        h.move(7 * MINUTE) { at -> if (h.desk.running.value?.ride != null) calle5Route.pointAt((at - t31At) * 7.0 / 1000) else null }
        assertEquals(2, h.desk.running.value?.index)
        assertEquals(listOf("A47"), h.awaitedLines)
        assertEquals(0, h.waitServiceStarts)
        assertOne()
        // Shown as awaited on the rows' bells
        assertTrue(waits.running.value.any { it.key.vehicleId == "261001" })
        h.desk.finish()
        h.advance(1)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun theButtonsTakeTheLineTappedAndEndTheRest() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("I took the E21")
        h.advance(1)
        assertEquals("E21", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.awaitedLines.isEmpty())
        assertTrue(waits.running.value.isEmpty())
        // The ride's own notice from here: no lines to take
        assertEquals(listOf("Finish"), shown.actions.map { it.title.toString() })
    }

    @Test
    fun aPlannerRideWarnsBeforeItsStopAsASingleRideDoes() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("I took the T31")
        val t31At = h.now()
        // At 7 m/s, two stops before the change after 4 min
        h.move(4 * MINUTE) { at -> calle5Route.pointAt((at - t31At) * 7.0 / 1000) }
        assertEquals(true, h.desk.running.value?.ride?.alerted)
        assertEquals(TripChannel.WARNING_SILENT.id, shown.channelId)
        assertTrue(shown.title(), shown.title().startsWith("Get off at Calle 5 con Kr 39"))
    }

    @Test
    fun aTripStartedInItsPlaceEndsAPlannerTripsWaits() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, waits.running.value.size)
        h.start(TripFixtures.plan)
        h.advance(1)
        assertEquals(TripEnding.REPLACED, h.desk.ended.value?.ending)
        assertTrue(h.awaitedLines.toString(), h.awaitedLines.isEmpty())
        assertTrue(waits.running.value.isEmpty())
        assertEquals("E21", h.desk.running.value?.ride?.plan?.line)
    }

    @Test
    fun aButtonLeftFromAnEarlierTripTakesNothing() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.app.sendBroadcast(
            android.content.Intent(h.app, TripActionReceiver::class.java)
                .setAction(TripNotifications.ACTION_TAKE_LINE)
                .putExtra(TripNotifications.EXTRA_STARTED_AT, 1L)
                .putExtra(TripNotifications.EXTRA_LINE, "T31"),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(null, h.desk.running.value?.ride)
    }

    @Test
    fun theAlertComesOnceAsTheSoonestBusNears() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        assertEquals(TripChannel.TRIPS.id, shown.channelId)
        assertEquals(null, h.desk.running.value?.busAlertedFor)
        // The E21, due at 8 min, within 2 min from 6: the alert, on the channel the settings ask, marked as gone out
        h.move(MINUTE + 15_000) { stop }
        assertEquals(1, h.desk.running.value?.busAlertedFor)
        assertEquals(WaitChannel.ALERT_SILENT.id, shown.channelId)
        // Every later post silent, on the same channel
        h.move(MINUTE) { stop }
        assertTrue(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(WaitChannel.ALERT_SILENT.id, shown.channelId)
    }

    @Test
    fun theAlertIsItsNoticeOnceARide() {
        val journey = com.yenreh.caligo.domain.Journey.planned(option, DESTINATION, NOW).fixed(JourneyFixtures.fixAt(stop, NOW + MINUTE))
        val words = TripWords(h.app.resources)
        val near = listOf(AwaitedLine("E21", NOW + 3 * MINUTE), AwaitedLine("T31", NOW + 5 * MINUTE))
        val notice = TripNotice.of(journey, NOW + 90_000, com.yenreh.caligo.waits.Alerts(), words, 15_000, awaited = near, near = true)
        assertTrue(notice.alerting)
        assertEquals(WaitChannel.ALERT_SILENT.id, notice.channelId)
        // Not before the 2 minutes
        assertFalse(TripNotice.of(journey, NOW, com.yenreh.caligo.waits.Alerts(), words, 15_000, awaited = near, near = true).alerting)
        // Once gone out, never again for the ride
        val after = TripNotice.of(journey.copy(busAlertedFor = 1), NOW + 2 * MINUTE, com.yenreh.caligo.waits.Alerts(), words, 15_000, awaited = near, near = true)
        assertFalse(after.alerting)
        assertEquals(WaitChannel.ALERT_SILENT.id, after.channelId)
        // With the alert channel off, no alert, every post on the trips' channel
        val off = TripNotice.of(journey, NOW + 90_000, com.yenreh.caligo.waits.Alerts(), words, 15_000, awaited = near, near = true, busAlertOn = false)
        assertFalse(off.alerting)
        assertEquals(TripChannel.TRIPS.id, off.channelId)
    }

    @Test
    fun theRidersOwnWaitsKeepTheirNotificationsApart() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        val bus = WaitFixtures.bus("X1", "x1", NOW + 30 * MINUTE)
        assertEquals(WaitStart.STARTED, waits.start(WaitFixtures.awaited(bus), null))
        h.advance(1)
        assertOne()
        assertEquals(listOf("X1"), h.waitTitles.map { it.substringBefore(" ") })
        h.desk.finish()
        h.advance(1)
        // Their own wait goes on
        assertEquals(listOf("X1"), h.waitTitles.map { it.substringBefore(" ") })
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun aBellTappedOnABusTheTripAwaitsStopsAwaitingItAndIsNotAskedForAgain() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        val asked = h.source.arrivalsAsked.size
        val e21 = waits.running.value.first { it.key.vehicleId == "637001" }.key
        waits.stop(e21)
        h.advance(1)
        assertEquals(listOf("T31"), h.awaitedLines)
        assertFalse(shown.text(), "E21 in" in shown.text())
        waits.stop(waits.running.value.single().key)
        h.move(3 * MINUTE) { walking(it) }
        assertEquals(asked, h.source.arrivalsAsked.size)
        assertTrue(h.awaitedLines.isEmpty())
        h.assertRunning()
    }

    @Test
    fun theRidersOwnWaitsKeepTheirCapApartFromATripsAwaitedBuses() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, waits.running.value.size)
        for (i in 1..Wait.CAP) {
            val bus = WaitFixtures.bus("X$i", "x$i", NOW + 30 * MINUTE)
            assertEquals(WaitStart.STARTED, waits.start(WaitFixtures.awaited(bus), null))
        }
        assertEquals(WaitStart.CAP_REACHED, waits.start(WaitFixtures.awaited(WaitFixtures.bus("X9", "x9", NOW + 30 * MINUTE)), null))
        // A bus the trip awaits already counts as waited for
        val tripKey = waits.running.value.first { it.key.vehicleId == "637001" }.key
        val e21 = firstArrivals(NOW).stops.single().arrivals.first { it.vehicleId == tripKey.vehicleId }
        assertEquals(WaitStart.ALREADY_WAITING, waits.start(com.yenreh.caligo.domain.AwaitedBus(out[0].stopId, out[0].name, e21, stop), null))
    }

    @Test
    fun iTookItOnTheRidersOwnWaitForALineTheTripAwaitsTakesIt() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        val key = waits.running.value.first { it.key.vehicleId == "815001" }.key
        assertTrue(h.desk.took(TookRide("T31", "Universidades", key.stopId, out[0].name, key.vehicleId, key.trip)))
        h.advance(1)
        assertEquals("T31", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.awaitedLines.isEmpty())
    }

    @Test
    fun aBusTakenByPositionEndsEveryWaitOfItsRide() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        val leftAt = h.now()
        h.source.byLine = { line, at -> if (line == "E21") listOf(busOn(calle5Route, "11002", 20 + (at - leftAt) * 8.0 / 1000)) else emptyList() }
        h.move(MINUTE) { at -> calle5Route.pointAt((at - leftAt) * 8.0 / 1000) }
        assertEquals("E21", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.awaitedLines.toString(), h.awaitedLines.isEmpty())
        assertTrue(waits.running.value.isEmpty())
    }

    // Endings

    @Test
    fun finishMidWalk() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, h.awaitedLines.size)
        h.desk.finish()
        h.advance(1)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun finishOnTheNotificationWhileWaitingAtTheStop() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("Finish")
        h.advance(1)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun swipedAwayWhileWaiting() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.swipe()
        h.advance(1)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.DISMISSED)
    }

    @Test
    fun swipedAwayMidRide() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("I took the T31")
        h.advance(1)
        h.swipe()
        h.advance(1)
        h.assertEnded(TripEnding.DISMISSED)
    }

    @Test
    fun notificationsTurnedOffMidWalk() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        shadowOf(h.manager).setNotificationsEnabled(false)
        h.advance(TripEngine.BEAT.inWholeMilliseconds)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.BLOCKED)
    }

    @Test
    fun locationOffWhileWaiting() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.location.lose()
        h.advance(1)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.NO_LOCATION)
    }

    @Test
    fun threeHoursEndAWholeTrip() {
        startJourney()
        // Never at the stop, never gone from it: only the limit ends it
        h.move(com.yenreh.caligo.domain.Journey.TIME_LIMIT.inWholeMilliseconds + MINUTE) { origin }
        h.assertEnded(TripEnding.TIME_LIMIT)
        assertEquals("Trip ended: watch for your stop", checkNotNull(h.endedNotice).title())
        assertTrue(waits.running.value.isEmpty())
    }

    @Test
    fun aKilledServiceLeavesNoAwaitedBus() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, waits.running.value.size)
        h.service.onDestroy()
        assertTrue(waits.running.value.isEmpty())
        assertTrue(h.tripOwn.isEmpty())
    }

    @Test
    fun awaitedBusesAllGoneWithNoneTakenAreAskedForAgain() {
        // The buses drop out of the waits' answers: no longer seen, the waits end by themselves
        var listed = true
        h.waitsAnswering { _, at -> WaitAnswer(if (listed) firstArrivals(NOW).stops else emptyList(), at) }
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, h.awaitedLines.size)
        val asked = h.source.arrivalsAsked.size
        listed = false
        h.move(9 * MINUTE) { stop }
        assertTrue(h.source.arrivalsAsked.size > asked)
        assertEquals(2, h.awaitedLines.size)
    }

    // Waiting at the stop

    @Test
    fun waitingAsksBalancedFixesUntilABusOfTheRideNearsTheStop() {
        // Awaited buses 20 and 25 min away: only the round's buses count
        h.source.arrivalsAnswer = { _, at -> firstArrivals(at, e21In = 20, t31In = 25) }
        h.waitsAnswering { _, at -> WaitAnswer(firstArrivals(NOW, e21In = 20, t31In = 25).stops, at) }
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        assertTrue(h.desk.running.value?.phase(h.now()) is JourneyPhase.Waiting)
        assertEquals(FixQuality.BALANCED, h.location.requested)
        // An E21 400 m before the stop, in the next round
        h.source.byLine = { line, _ -> if (line == "E21") listOf(busOn(calle5Route, "41001", 0.0).copy(longitude = origin.longitude - 100 / 111320.0)) else emptyList() }
        h.move(TripEngine.BUS_INTERVAL.inWholeMilliseconds + 15_000) { stop }
        assertEquals(FixQuality.HIGH, h.location.requested)
        // Gone from the round: balanced again
        h.source.byLine = { _, _ -> emptyList() }
        h.move(TripEngine.BUS_INTERVAL.inWholeMilliseconds + 15_000) { stop }
        assertEquals(FixQuality.BALANCED, h.location.requested)
    }

    @Test
    fun waitingAsksHighFixesOnceAnAwaitedBusIsDueWithinTwoMinutes() {
        startJourney()
        // The E21 is due at 8 min
        h.move(5 * MINUTE + 30_000) { toTheStop(it) }
        assertEquals(FixQuality.BALANCED, h.location.requested)
        h.move(MINUTE) { stop }
        assertEquals(FixQuality.HIGH, h.location.requested)
    }

    @Test
    fun aRiderGoneFromTheStopEndsTheTripAndAsksNoMoreBuses() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        val asked = h.source.askedLines.size
        // Away north in a taxi at 5 m/s, off the ride's route
        val leftAt = h.now()
        h.move(5 * MINUTE) { at -> GeoPoint(stop.latitude + (at - leftAt) * 5.0 / 1000 / 111320.0, stop.longitude) }
        h.assertEnded(TripEnding.LEFT_STOP)
        assertTrue(waits.running.value.isEmpty())
        // Gone elsewhere, on no bus of the trip: its last word is silent
        val last = checkNotNull(h.endedNotice)
        assertEquals(TripChannel.TRIPS.id, last.channelId)
        assertEquals("Trip ended", last.title())
        assertEquals("The trip ended: you left the stop.", last.text())
        // Asked while near, not once gone 300 m
        assertTrue(h.source.askedLines.toString(), h.source.askedLines.size - asked <= 6)
    }

    @Test
    fun aRiderWhoStaysAtTheStopWithNoBusTakenEndsTheTripAt45Minutes() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.assertRunning()
        h.move(com.yenreh.caligo.domain.Journey.WAITING_LIMIT.inWholeMilliseconds - 2 * MINUTE) { stop }
        h.assertRunning()
        h.move(2 * MINUTE) { stop }
        h.assertEnded(TripEnding.NO_BUS)
        assertTrue(waits.running.value.isEmpty())
        // Maybe on a bus the trip never told: the stop to get off at is still ahead
        val last = checkNotNull(h.endedNotice)
        assertEquals(TripChannel.WARNING_SILENT.id, last.channelId)
        assertEquals("Trip ended: watch for your stop", last.title())
        assertEquals("The trip ended: no bus taken in 45${NBSP}min.", last.text())
    }

    @Test
    fun theWalkAsksForNoBusesUntilNearTheStop() {
        startJourney()
        // 600 m away: no line's buses, however long
        h.move(2 * MINUTE) { JourneyFixtures.east(stop, -600.0) }
        assertTrue(h.source.askedLines.toString(), h.source.askedLines.isEmpty())
        // Within 150 m: the buses of each line that makes the ride
        h.move(30_000) { JourneyFixtures.east(stop, -150.0) }
        assertEquals(setOf("E21", "T31"), h.source.askedLines.toSet())
    }
}
