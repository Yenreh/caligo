package com.yenreh.caligo.trips

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A planner trip on virtual time, with scripted fixes, buses and arrivals:
 * the walk to the stop, the buses awaited for each line that makes the
 * first ride, "T31: I took it" on the trip's notification, the change on
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
        assertEquals(listOf("E21: I took it", "T31: I took it", "Finish"), shown.actions.map { it.title.toString() })
        // The T31 taken: every wait of the ride ends
        h.pressTitled("T31: I took it")
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
        h.pressTitled("T31: I took it")
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
        h.advance(window)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun theButtonsTakeTheLineTappedAndEndTheRest() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("E21: I took it")
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
        h.pressTitled("T31: I took it")
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
        h.advance(window)
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
    fun iTookItOnTheRidersOwnWaitIsNotTakenByATripFinishingSoANewOneStarts() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        val key = waits.running.value.first { it.key.vehicleId == "815001" }.key
        h.desk.finish()
        val took = TookRide("T31", "Universidades", key.stopId, out[0].name, key.vehicleId, key.trip)
        assertFalse(h.desk.took(took))
        h.assertFinishing("Trip to $DESTINATION")
        // The trip screen it opens starts a ride of its own, which ends the finishing trip as finished
        h.start(TripFixtures.plan.copy(took = took))
        assertEquals(TripEnding.FINISHED, h.desk.ended.value?.ending)
        assertTrue("the old trip's waits gone", h.awaitedLines.isEmpty())
        h.assertRunning()
        assertEquals(1, h.tripOwn.size)
    }

    // A forgotten or late "I took it"

    /** The waits' answers: the E21 due at 8 and the T31 at 10 listed until about their time, then gone, as they leave */
    private fun leaving() = h.waitsAnswering { _, at ->
        val buses = listOfNotNull(
            com.yenreh.caligo.domain.entities.BusArrival("E21", "Universidades", NOW + 8 * MINUTE, "637001", out[0].name).takeIf { at < NOW + 8 * MINUTE - 30_000 },
            com.yenreh.caligo.domain.entities.BusArrival("T31", "Universidades", NOW + 10 * MINUTE, "815001", out[0].name).takeIf { at < NOW + 10 * MINUTE - 30_000 },
        )
        WaitAnswer(
            listOf(com.yenreh.caligo.domain.entities.NearbyStop(out[0].stopId, out[0].name, 20.0, buses, out[0].latitude, out[0].longitude)) +
                secondArrivals(NOW + 12 * MINUTE).stops,
            at,
        )
    }

    /** At the stop from 4 minutes, the E21 gone at 8 and asked about from 9 */
    private fun atTheStopAsTheE21Leaves() {
        leaving()
        startJourney()
        h.move(9 * MINUTE + 20_000) { toTheStop(it) }
    }

    @Test
    fun asTheBusAwaitedLeavesTheTripAsksSilentlyWhetherTheRiderTookIt() {
        atTheStopAsTheE21Leaves()
        assertOne()
        assertEquals("Did you take the E21?", shown.title())
        assertEquals(listOf("Yes", "No", "Finish"), shown.actions.map { it.title.toString() })
        assertTrue("silent", shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(h.desk.awaited.value.any { it.line == "E21" && it.asking })
    }

    @Test
    fun yesTakesTheLineFromWhenItsBusLeft() {
        atTheStopAsTheE21Leaves()
        h.pressTitled("Yes")
        h.advance(1)
        val ride = checkNotNull(h.desk.running.value?.ride)
        assertEquals("E21", ride.plan.line)
        assertEquals(NOW + 8 * MINUTE, ride.startedAt)
        assertTrue(h.awaitedLines.isEmpty())
    }

    @Test
    fun noGoesBackToWaitingForTheNextBus() {
        atTheStopAsTheE21Leaves()
        h.pressTitled("No")
        h.advance(1)
        assertNull(h.desk.running.value?.ride)
        assertEquals(listOf("T31"), h.awaitedLines)
        assertEquals("Take the T31 or E21", shown.title())
        assertEquals(listOf("T31: I took it", "E21: I took it", "Finish"), shown.actions.map { it.title.toString() })
    }

    @Test
    fun unansweredTheAskGoesAfterItsMinute() {
        atTheStopAsTheE21Leaves()
        h.move(MINUTE) { stop }
        assertNull(h.desk.running.value?.ride)
        assertTrue(shown.title(), shown.title().startsWith("Take the"))
        assertEquals(listOf("T31"), h.awaitedLines)
    }

    @Test
    fun aTapSixMinutesAfterTheBusLeftStartsTheRideFromItsLeaving() {
        atTheStopAsTheE21Leaves()
        h.move(5 * MINUTE - 20_000) { null }
        // 14:00, the E21 gone at 8
        assertEquals(NOW + 14 * MINUTE, h.now())
        h.desk.takeLine("E21")
        h.advance(1)
        val ride = checkNotNull(h.desk.running.value?.ride)
        assertEquals(NOW + 8 * MINUTE, ride.startedAt)
        // About six minutes along by the estimate: the ride of six to Calle 5 con Kr 39 nearly done
        assertTrue(ride.progress(h.now()).along > 0.7 * ride.route.at[ride.plan.target])
    }

    @Test
    fun leavingAtABusPaceSoonAfterItsBusLeftTakesTheLineWithNoBusPlaced() {
        atTheStopAsTheE21Leaves()
        // No bus positions at all
        h.source.byLine = { _, _ -> emptyList() }
        val leftAt = h.now()
        h.move(MINUTE) { at -> calle5Route.pointAt(40 + (at - leftAt) * 8.0 / 1000) }
        val ride = checkNotNull(h.desk.running.value?.ride)
        assertEquals("E21", ride.plan.line)
        assertEquals(NOW + 8 * MINUTE, ride.startedAt)
    }

    @Test
    fun withALineChosenOnlyItsBusAsks() {
        leaving()
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.desk.chooseLine("T31")
        h.move(4 * MINUTE + 20_000) { stop }
        // The E21 left at 8: nothing asked of it
        assertEquals("Take the T31", shown.title())
        h.move(2 * MINUTE) { stop }
        assertEquals("Did you take the T31?", shown.title())
    }

    // Choosing one of the ride's lines

    private val waitAsks get() = (h.app.container.waits.source as com.yenreh.caligo.waits.FakeWaitSource).asked.size

    @Test
    fun aLineChosenIsTheRidesAloneUntilAnyAndTheNextRideTakesAnyOfItsOwn() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        assertEquals(listOf("E21", "T31"), h.awaitedLines)
        val arrivals = h.source.arrivalsAsked.size
        val asks = waitAsks

        // The T31 alone: the E21's wait stops, not as declined, and nothing more is asked
        h.desk.chooseLine("T31")
        h.advance(1)
        assertEquals(listOf("T31"), h.awaitedLines)
        assertEquals("T31", h.desk.running.value?.chosenLine)
        assertNull(h.desk.running.value?.declinedFor)
        assertEquals(arrivals, h.source.arrivalsAsked.size)
        assertEquals(asks, waitAsks)
        assertEquals("Take the T31", shown.title())
        assertEquals(listOf("T31: I took it", "Finish"), shown.actions.map { it.title.toString() })
        assertTrue(shown.text(), "E21" !in shown.text())
        // Near the stop every line's buses still, to tell the one the rider leaves on
        val rounds = h.source.askedLines.size
        h.move(MINUTE) { stop }
        assertEquals(setOf("E21", "T31"), h.source.askedLines.drop(rounds).toSet())
        assertOne()

        // Any again: each line awaited again, after one ask
        h.desk.chooseLine(null)
        h.advance(1)
        assertEquals(arrivals + 1, h.source.arrivalsAsked.size)
        // The T31's wait kept as it was, the E21's started again
        assertEquals(setOf("E21", "T31"), h.awaitedLines.toSet())
        assertEquals(2, h.awaitedLines.size)
        assertTrue(shown.title(), shown.title() == "Take the E21 or T31" || shown.title() == "Take the T31 or E21")
        h.move(MINUTE) { stop }
        assertEquals(arrivals + 1, h.source.arrivalsAsked.size)

        // The T31 again, taken: the A47's ride after it starts on any of its lines
        h.desk.chooseLine("T31")
        h.pressTitled("T31: I took it")
        h.advance(1)
        assertNull(h.desk.running.value?.chosenLine)
        h.desk.finish()
        h.advance(window)
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun aRiderWhoChoseALineYetLeavesOnAnotherIsFollowedOnIt() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.desk.chooseLine("T31")
        h.advance(1)
        // Away on an E21 after all, beside it
        val leftAt = h.now()
        h.source.byLine = { line, at -> if (line == "E21") listOf(busOn(calle5Route, "11002", 20 + (at - leftAt) * 8.0 / 1000)) else emptyList() }
        h.move(MINUTE) { at -> calle5Route.pointAt((at - leftAt) * 8.0 / 1000) }
        assertEquals("E21", h.desk.running.value?.ride?.plan?.line)
        assertTrue(h.awaitedLines.isEmpty())
    }

    @Test
    fun aLineChosenWithNoWaitOfItsOwnIsAskedForAtOnce() {
        // Only the E21 listed at first
        h.source.arrivalsAnswer = { _, at -> LiveArrivals(at, firstArrivals(at).stops.map { s -> s.copy(arrivals = s.arrivals.filter { it.line == "E21" }) }, answeredAt = mapOf(out[0].stopId to at)) }
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        assertEquals(listOf("E21"), h.awaitedLines)
        val arrivals = h.source.arrivalsAsked.size
        h.source.arrivalsAnswer = { stops, at -> if (stops.any { it.stopId == out[0].stopId }) firstArrivals(at) else secondArrivals(at) }
        h.desk.chooseLine("T31")
        h.advance(1)
        assertEquals(arrivals + 1, h.source.arrivalsAsked.size)
        assertEquals(listOf("T31"), h.awaitedLines)
    }

    @Test
    fun aChoiceLeavesNothingBehindAsTheTripEnds() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.desk.chooseLine("E21")
        h.advance(1)
        assertTrue(h.desk.rideBuses.value != null)
        h.swipe()
        h.assertEnded(TripEnding.DISMISSED)
        assertNull(h.desk.rideBuses.value)
        assertTrue(waits.running.value.isEmpty())
    }

    @Test
    fun theRidesLinesAndTheirBusesAreHeardWithNoRequestOfTheirOwn() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        val heard = checkNotNull(h.desk.rideBuses.value)
        val lines = heard.lines(checkNotNull(h.desk.running.value), h.now())
        assertEquals(listOf("E21", "T31"), lines.map { it.line })
        // The E21 due at 8, the T31 at 10, as the waits' answers list them
        assertEquals(listOf(3), lines[0].times.map { it.minutes })
        assertEquals(listOf(5), lines[1].times.map { it.minutes })
        assertTrue(lines.all { row -> row.times.all { it.reachable && !it.estimated } })
    }

    // Asking for the next ride's buses

    /** Minutes from the start each ride's arrivals were asked at */
    private val askedMinutes get() = h.source.arrivalsAskedAt.map { (it - NOW) / MINUTE.toDouble() }

    @Test
    fun withNoBusOfTheRideListedItsArrivalsAreAskedAfter1Then2Then4Minutes() {
        h.source.arrivalsAnswer = { _, at -> LiveArrivals(at, emptyList()) }
        startJourney()
        h.move(12 * MINUTE) { stop }
        assertEquals(listOf(0.0, 1.0, 3.0, 7.0, 11.0), askedMinutes)
    }

    @Test
    fun aBusListedAgainResetsTheWait() {
        var listed = false
        h.source.arrivalsAnswer = { _, at -> if (listed) firstArrivals(at, e21In = 3, t31In = 4) else LiveArrivals(at, emptyList()) }
        // The waits see those buses until they reach the stop, at 10 and 11
        h.waitsAnswering { _, at -> WaitAnswer(if (at < NOW + 9 * MINUTE) firstArrivals(NOW + 7 * MINUTE, e21In = 3, t31In = 4).stops else emptyList(), at) }
        startJourney()
        h.move(4 * MINUTE) { stop }
        assertEquals(listOf(0.0, 1.0, 3.0), askedMinutes)
        // Listed at 7: its waits run, and pass with none taken
        listed = true
        h.move(4 * MINUTE) { stop }
        assertEquals(7.0, askedMinutes.last(), 0.0)
        listed = false
        h.move(12 * MINUTE) { stop }
        // Once they ended, none listed again: a minute, not four, as the wait starts over
        val after = askedMinutes.filter { it > 7.0 }
        assertTrue(after.toString(), after.size >= 2)
        assertEquals(after.toString(), 1.0, after[1] - after[0], 0.0)
    }

    @Test
    fun aFailingAskWaitsLongerEachTimeUpTo2MinutesMore() {
        h.source.arrivalsAnswer = { _, _ -> throw com.yenreh.caligo.data.datasources.NetworkApiException("offline") }
        startJourney()
        h.move(10 * MINUTE) { stop }
        // 1 min and 30 s, then 2 min, then 3 min each
        assertEquals(listOf(0.0, 1.5, 3.5, 6.5, 9.5), askedMinutes)
    }

    @Test
    fun theStopsToGetOffAtAreAskedOnlyForAStationsPlatform() {
        startJourney()
        // Plaza de Cayzedo A1, a station's platform: where the ride gets off too, once each
        assertEquals(listOf(out[0].stopId, out[6].stopId), h.source.arrivalsAsked.first())
        h.move(5 * MINUTE) { toTheStop(it) }
        h.pressTitled("T31: I took it")
        val t31At = h.now()
        h.source.byLine = { line, at -> if (line == "T31") listOf(busOn(calle5Route, "41022", (at - t31At) * 7.0 / 1000)) else emptyList() }
        h.move(7 * MINUTE) { at -> if (h.desk.running.value?.ride != null) calle5Route.pointAt((at - t31At) * 7.0 / 1000) else null }
        // Kr 22 con Cl 10, a street stop: only where the A47 boards
        assertEquals(listOf(a47[0].stopId), h.source.arrivalsAsked.last())
    }

    @Test
    fun waitingWithNoFreshFixTheBusesDirectionsAreAskedNearEachLinesStop() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        h.move(2 * MINUTE) { null }
        val asked = h.source.askedLines.zip(h.source.askedNear).takeLast(2).toMap()
        assertEquals(setOf("E21", "T31"), asked.keys)
        for (near in asked.values) assertEquals(stop, near)
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

    private val window = TripEngine.UNDO_WINDOW.inWholeMilliseconds

    @Test
    fun finishMidWalk() {
        startJourney()
        h.move(MINUTE) { walking(it) }
        assertEquals(2, h.awaitedLines.size)
        h.desk.finish()
        // Its waits run on underneath, shown on the bells, until the Finish takes effect
        h.move(window - 1_000) { walking(it) }
        h.assertFinishing("Trip to $DESTINATION")
        assertEquals(2, h.awaitedLines.size)
        assertEquals(2, waits.running.value.size)
        h.advance(1_000)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun finishOnTheNotificationWhileWaitingAtTheStop() {
        startJourney()
        // The E21 due at 8 min: within 2 min of it from 6
        h.move(5 * MINUTE + 55_000) { toTheStop(it) }
        h.pressTitled("Finish")
        h.assertFinishing("Trip to $DESTINATION")
        // No bus alert, though it is due meanwhile
        h.move(window - 1_000) { stop }
        assertTrue(h.tripOwn.none { it.channelId?.startsWith("alert") == true })
        assertNull(h.desk.finishing.value?.journey?.busAlertedFor)
        h.advance(1_000)
        assertTrue(waits.running.value.isEmpty())
        h.assertEnded(TripEnding.FINISHED)
    }

    @Test
    fun undoWhileWaitingAtTheStopGivesTheTripBackWithItsWaitsAndButtons() {
        startJourney()
        h.move(5 * MINUTE) { toTheStop(it) }
        val asked = h.source.arrivalsAsked.size
        h.pressTitled("Finish")
        h.move(window / 2) { stop }
        h.pressTitled("Undo")
        h.assertRunning()
        // Nothing restarted: the same waits, not asked for again
        assertEquals(listOf("E21", "T31"), h.awaitedLines)
        assertEquals(asked, h.source.arrivalsAsked.size)
        assertEquals("Take the E21 or T31", shown.title())
        assertEquals(listOf("E21: I took it", "T31: I took it", "Finish"), shown.actions.map { it.title.toString() })
        h.pressTitled("T31: I took it")
        h.advance(1)
        assertEquals("T31", h.desk.running.value?.ride?.plan?.line)
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
        h.pressTitled("T31: I took it")
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
