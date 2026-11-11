package com.yenreh.caligo.ui.trips

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.trips.FakeTripControl
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.stops.FakeNotificationAccess
import com.yenreh.caligo.ui.stops.FakeWaitControl
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.waits.WaitControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Waits as a trip screen ends them, recorded */
class TookWaits : WaitControl by FakeWaitControl() {
    val took = mutableListOf<WaitKey>()

    override fun took(key: WaitKey) {
        took.add(key)
    }
}

/** The trip screen: where to get off, skipping it, changing it, and Finish */
@RunWith(AndroidJUnit4::class)
class TripScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeStopsRepository(emptyList(), emptyList()).apply { routes = mapOf("E21" to TripFixtures.route) }
    private val trips = FakeTripControl()
    private val waits = TookWaits()
    private val location = FakeLocationSource(permission = LocationPermission.GRANTED)
    private val notifications = FakeNotificationAccess(granted = true)
    private var closed = 0

    private fun show(took: TookRide? = TripFixtures.took): TripViewModel {
        val viewModel = TripViewModel(took, repository, trips, waits, location, notifications, now = { NOW })
        compose.setContent { AtTime(NOW) { CaliGoTheme { TripRoute(viewModel, onBack = { closed++ }) } } }
        return viewModel
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(tag) }

    private fun choose(stopId: String) {
        compose.onNodeWithTag("trip_choose").performScrollToNode(hasTestTag("trip_choose_$stopId"))
        compose.onNodeWithTag("trip_choose_$stopId").performClick()
    }

    @Test
    fun itAsksWhereTheRiderGetsOffAmongTheStopsAhead() {
        show()
        waitFor("trip_choose")
        compose.onNode(hasText("Where do you get off?")).assertExists()
        // Not the boarding stop, nor the way back
        assertTrue(!exists("trip_choose_${TripFixtures.out[0].stopId}"))
        assertTrue(exists("trip_choose_${TripFixtures.out[1].stopId}"))
        assertTrue(!exists("trip_choose_${TripFixtures.back[1].stopId}"))
        compose.onNode(hasText("E21 to Universidades")).assertExists()
        compose.onNode(hasText("From Plaza de Cayzedo A1")).assertExists()
        // The estimate from the boarding stop, marked as one
        compose.onNode(hasText("~1${NBSP}min")).assertExists()
        compose.assertControlsAccessible()
    }

    @Test
    fun openingItEndsTheWaitAsTaken() {
        show()
        waitFor("trip_choose")
        assertEquals(listOf(TripFixtures.took.waitKey), waits.took)
    }

    @Test
    fun choosingAStopStartsTheTripToIt() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_running")
        val plan = trips.started.single()
        assertEquals(7, plan.alight)
        assertEquals(0, plan.direction)
        compose.onNodeWithTag("trip_heading").assertExists()
        compose.onNode(hasText("Get off at Pampalinda")).assertExists()
        compose.onNode(hasText("~10${NBSP}min · 7${NBSP}stops")).assertExists()
    }

    @Test
    fun skippingJustFollowsTheRide() {
        show()
        waitFor("trip_skip")
        compose.onNodeWithTag("trip_skip").performClick()
        waitFor("trip_running")
        assertNull(trips.started.single().alight)
        compose.onNode(hasText("Following the ride to Universidades")).assertExists()
    }

    @Test
    fun theStopToGetOffAtCanBeChanged() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_running")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_stop_${TripFixtures.out[9].stopId}"))
        compose.onNodeWithTag("trip_stop_${TripFixtures.out[9].stopId}").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.alights.isNotEmpty() }
        assertEquals(listOf<Int?>(9), trips.alights)
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_heading"))
        compose.onNode(hasText("Get off at Universidades")).assertExists()
        compose.assertControlsAccessible()
        // Said, with a way back to the stop before
        compose.onNode(hasText("You get off at Universidades now")).assertExists()
        compose.onNode(hasText("Undo")).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.alights.size == 2 }
        assertEquals(listOf<Int?>(9, 7), trips.alights)
    }

    @Test
    fun aSingleRideSaysItsStopsAreATapToGetOffAndMarksTheOneChosen() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_running")
        compose.onNode(hasText("Tap a stop to get off there")).assertExists()
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_alight"))
        // Chosen, not disabled
        compose.onNodeWithTag("trip_alight").assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_stop_${TripFixtures.out[9].stopId}"))
        compose.onNodeWithTag("trip_stop_${TripFixtures.out[9].stopId}").assertHasClickAction().assertIsNotSelected()
    }

    @Test
    fun undoAfterAStopChosenOnARideFollowedGoesBackToFollowingIt() {
        show()
        waitFor("trip_skip")
        compose.onNodeWithTag("trip_skip").performClick()
        waitFor("trip_running")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_stop_${TripFixtures.out[5].stopId}"))
        compose.onNodeWithTag("trip_stop_${TripFixtures.out[5].stopId}").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.alights.isNotEmpty() }
        compose.onNode(hasText("Undo")).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.alights.size == 2 }
        assertEquals(listOf(5, null), trips.alights)
    }

    @Test
    fun endTripShowsItFinishedWithUndoAndStays() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_finish")
        compose.onNodeWithTag("trip_finish").assert(hasText("End trip"))
        compose.onNodeWithTag("trip_finish").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.finished == 1 }
        waitFor("trip_ended")
        compose.onNode(hasText("The trip is finished.")).assertExists()
        compose.onNode(hasText("Trip finished")).assertExists()
        assertEquals(0, closed)
        compose.assertControlsAccessible()

        // Undo: the trip runs again, shown as it was
        compose.onNode(hasText("Undo")).performClick()
        waitFor("trip_running")
        assertEquals(1, trips.undone)
        compose.onNode(hasText("Get off at Pampalinda")).assertExists()
        assertTrue(compose.onAllNodes(hasText("Trip finished")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun oneFinishShowsOneUndoForItsWholeWindowThoughTheTripMovesUnderneath() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_finish")
        compose.onNodeWithTag("trip_finish").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val shown = compose.onAllNodes(hasText("Trip finished")).fetchSemanticsNodes().map { it.id }
        // A fix, a bus round and a step change the trip under the Finish
        for (along in listOf(400.0, 800.0, 1200.0)) {
            val finishing = checkNotNull(trips.finishing.value)
            trips.finishing.value = finishing.copy(journey = finishing.journey.fixed(TripFixtures.fix(along, NOW)))
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        // The same snackbar all along, never taken away and shown again
        assertEquals(1, shown.size)
        assertEquals(shown, compose.onAllNodes(hasText("Trip finished")).fetchSemanticsNodes().map { it.id })
    }

    @Test
    fun theUndoGoesAsTheFinishTakesEffect() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_finish")
        compose.onNodeWithTag("trip_finish").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        trips.expireFinish()
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasText("The trip is finished.")).assertExists()
        compose.onNode(hasText("Close")).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun iTookItWhileATripFinishesAsksWhereToGetOffForANewOne() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.finish()
        val took = TookRide("T31", "Universidades", TripFixtures.out[0].stopId, TripFixtures.out[0].name, "815001", NOW + 600_000)
        repository.routes = mapOf("T31" to TripFixtures.route)
        show(took)
        waitFor("trip_choose")
        assertTrue(trips.tookRides.isEmpty())
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_running")
        assertEquals(com.yenreh.caligo.domain.TripEnding.FINISHED, trips.ended.value?.ending)
        assertNull(trips.finishing.value)
    }

    @Test
    fun withoutTheLocationNoTripStartsAndItSaysWhy() {
        location.permission = LocationPermission.DENIED
        location.answer = LocationPermission.DENIED
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_message")
        compose.onNode(hasText("CaliGo follows the ride with your location", substring = true)).assertExists()
        assertEquals(1, location.requests)
        assertTrue(trips.started.isEmpty())
    }

    @Test
    fun withLocationOffNoTripStarts() {
        location.enabled = false
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_message")
        compose.onNode(hasText("Location is off", substring = true)).assertExists()
        assertTrue(trips.started.isEmpty())
    }

    @Test
    fun withoutNotificationsNoTripStarts() {
        notifications.granted = false
        notifications.answer = false
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_message")
        assertEquals(1, notifications.requests)
        assertTrue(trips.started.isEmpty())
    }

    @Test
    fun withTheTripsChannelOffNoTripStartsAndItsSettingsOpen() {
        trips.allowed = false
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_message")
        compose.onNode(hasText("notifications are off", substring = true)).assertExists()
        assertTrue(trips.started.isEmpty())
        compose.onNode(hasText("Settings")).performClick()
        assertEquals(1, trips.settingsOpened)
        assertEquals(0, notifications.settingsOpened)
    }

    @Test
    fun withTheWaitsChannelOffATripStillStarts() {
        notifications.enabled = false
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.started.isNotEmpty() }
        assertTrue(!exists("trip_message"))
    }

    @Test
    fun aStopTheLineDoesNotCallAtHasNoRide() {
        show(TripFixtures.took.copy(stopId = "nowhere"))
        compose.waitUntil(WAIT_TIMEOUT_MS) {
            compose.onAllNodes(hasText("there is no ride to follow", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(trips.started.isEmpty())
    }

    @Test
    fun openedWithoutARideItShowsTheTripRunning() {
        trips.start(TripFixtures.plan)
        show(took = null)
        waitFor("trip_running")
        compose.onNode(hasText("Get off at Pampalinda")).assertExists()
    }

    @Test
    fun theTripEndedSaysHow() {
        val ended = com.yenreh.caligo.domain.Journey.single(TripFixtures.plan, NOW)
        trips.ended.value = com.yenreh.caligo.trips.EndedTrip(ended, com.yenreh.caligo.domain.TripEnding.REACHED)
        show(took = null)
        waitFor("trip_ended")
        compose.onNode(hasText("You reached Pampalinda.")).assertExists()
    }

    @Test
    fun aTripTheSystemWouldNotStartSaysSo() {
        val refused = com.yenreh.caligo.domain.Journey.single(TripFixtures.plan, NOW)
        trips.ended.value = com.yenreh.caligo.trips.EndedTrip(refused, com.yenreh.caligo.domain.TripEnding.BLOCKED, refused = true)
        show(took = null)
        waitFor("trip_ended")
        compose.onNode(hasText("Android did not let the trip start", substring = true)).assertExists()
    }

    @Test
    fun withNoTripRunningNorEndedItSaysSoPlainly() {
        show(took = null)
        waitFor("trip_ended")
        compose.onNode(hasText("No trip is running.")).assertExists()
    }

    @Test
    fun restoredLongAfterItsRideItAsksNothingAndCloses() {
        // As the back stack is restored after the process went
        val old = TripFixtures.took.copy(trip = NOW - TripViewModel.STALE_TOOK.inWholeMilliseconds - 60_000)
        val viewModel = show(old)
        compose.waitUntil(WAIT_TIMEOUT_MS) { closed == 1 }
        assertTrue(viewModel.screen.value.stale)
        assertTrue(!exists("trip_choose"))
        assertTrue(waits.took.isEmpty())
        assertTrue(trips.starts.isEmpty())
    }

    @Test
    fun aRideTakenMinutesAgoStillAsksWhereToGetOff() {
        show(TripFixtures.took.copy(trip = NOW - 20 * 60_000))
        waitFor("trip_choose")
        assertEquals(0, closed)
    }

    // Why the trip goes by the estimate

    private fun estimated(journey: com.yenreh.caligo.domain.Journey, text: String) {
        trips.running.value = journey
        show(took = null)
        waitFor("trip_running")
        compose.onNode(hasText(text)).assertExists()
    }

    private val ride = com.yenreh.caligo.domain.Journey.single(TripFixtures.plan.copy(alight = 7), NOW)

    @Test
    fun noGoodFixYetSaysSo() = estimated(ride, "Estimated: no position yet")

    @Test
    fun goodFixesHadButNoneFreshSaySo() =
        estimated(ride.copy(fix = TripFixtures.fix(400.0, NOW - 5 * 60_000)), "Estimated: no recent position")

    @Test
    fun theApproximatePermissionAloneSaysPreciseLocationIsOff() =
        estimated(ride.copy(approximate = true), "Estimated: precise location is off")

    @Test
    fun onFootTheSameWordsSayWhy() {
        val walk = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW)
        estimated(walk.copy(approximate = true), "Estimated: precise location is off")
    }

    // A planner trip

    @Test
    fun aPlannerTripOnFootSaysTheWalkAndWhatComesAfter() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        show(took = null)
        waitFor("trip_running")
        compose.onNode(hasText("Trip to Unicentro")).assertExists()
        compose.onNode(hasText("Walk to Plaza de Cayzedo A1")).assertExists()
        compose.onNode(hasText("Then the E21 or T31")).assertExists()
        compose.assertControlsAccessible()
        compose.onNodeWithTag("trip_running").performScrollToNode(hasText("THEN"))
        compose.onNode(hasText("THEN")).assertExists()
        compose.onNode(hasText("E21 or T31 · get off at Calle 5 con Kr 39, 6${NBSP}stops")).assertExists()
    }

    @Test
    fun onFootTheAwaitedBusesShowSoonestFirstAsAtTheStop() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.awaited.value = listOf(
            com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 6 * 60_000),
            com.yenreh.caligo.trips.AwaitedLine("E21", NOW + 9 * 60_000),
        )
        show(took = null)
        waitFor("trip_awaited")
        compose.onNode(hasText("Then the T31 or E21")).assertExists()
        compose.onNode(hasText("T31 6${NBSP}min · E21 9${NBSP}min")).assertExists()
    }

    /** A planner trip on foot, its last fix [meters] from the stop to board at */
    private fun walkingAt(meters: Double) {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        val stop = com.yenreh.caligo.domain.GeoPoint(TripFixtures.out[0].latitude, TripFixtures.out[0].longitude)
        val at = com.yenreh.caligo.trips.JourneyFixtures.east(stop, -meters)
        trips.running.value = trips.running.value!!.fixed(com.yenreh.caligo.trips.JourneyFixtures.fixAt(at, NOW))
    }

    @Test
    fun onFootNearTheStopEachLineHasItsButtonAsAtTheStop() {
        walkingAt(100.0)
        trips.awaited.value = listOf(com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 2 * 60_000))
        show(took = null)
        waitFor("trip_took_T31")
        compose.onNode(hasText("As you board, tap the line you took")).assertExists()
        assertTrue(exists("trip_took_E21"))
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_took_E21"))
        compose.onNodeWithTag("trip_took_E21").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.takenLines.isNotEmpty() }
        assertEquals(listOf("E21"), trips.takenLines)
        compose.assertControlsAccessible()
    }

    @Test
    fun onFootFarFromTheStopNoLineHasAButton() {
        walkingAt(600.0)
        show(took = null)
        waitFor("trip_then")
        assertTrue(!exists("trip_took_E21"))
        assertTrue(!exists("trip_took_T31"))
    }

    @Test
    fun iTookItOnAWaitOfAPlannerTripTakesThatLineAsItsRide() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        val took = TookRide("T31", "Universidades", TripFixtures.out[0].stopId, TripFixtures.out[0].name, "815001", NOW + 600_000)
        show(took)
        waitFor("trip_running")
        assertEquals(listOf(took), trips.tookRides)
        assertEquals("T31", trips.running.value?.ride?.plan?.line)
        // The wait it came from ends as taken; nothing asks where to get off
        assertEquals(listOf(took.waitKey), waits.took)
        assertTrue(!exists("trip_choose"))
        compose.onNode(hasText("Get off at Calle 5 con Kr 39")).assertExists()
    }

    // The ride's lines at its stop

    private fun walkingWithBuses() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        val journey = checkNotNull(trips.running.value)
        // About 5 minutes on foot: the T31 in 2 cannot be made
        trips.rideBuses.value = com.yenreh.caligo.trips.JourneyFixtures.heard(
            journey,
            NOW,
            com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t1", NOW, 2.0),
            com.yenreh.caligo.trips.JourneyFixtures.bus("E21", "e1", NOW, 8.0),
            com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t2", NOW, 12.0),
            com.yenreh.caligo.trips.JourneyFixtures.bus("E21", "e2", NOW, 20.0),
            com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t3", NOW, 25.0),
            com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t4", NOW, 40.0),
        )
    }

    @Test
    fun onFootEachLineOfTheRideShowsItsNextBusesThoseOutOfReachSaidSo() {
        walkingWithBuses()
        show(took = null)
        waitFor("trip_lines")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_lines"))
        compose.onNode(hasText("LINES AT THIS STOP")).assertExists()
        compose.onNode(hasText("Tap one to see it and follow only that one")).assertExists()
        compose.onNodeWithTag("trip_line_any").assertIsSelected()
        compose.onNodeWithTag("trip_line_T31").assertIsNotSelected()
        compose.onNode(hasText("E21") and hasTestTag("trip_line_E21")).assertExists()
        // Three at most, the first out of reach on foot
        compose.onNodeWithTag("trip_line_row_T31").assert(
            androidx.compose.ui.test.hasContentDescription("T31, 2 minutes, 12 minutes, 25 minutes, the first you won't make"),
        )
        compose.onNodeWithTag("trip_line_row_E21").assert(androidx.compose.ui.test.hasContentDescription("E21, 8 minutes, 20 minutes"))
        compose.assertControlsAccessible()
    }

    @Test
    fun aLineTappedIsTheOneFollowedUntilAny() {
        walkingWithBuses()
        show(took = null)
        waitFor("trip_lines")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_lines"))
        compose.onNodeWithTag("trip_line_T31").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.chosenLines.isNotEmpty() }
        assertEquals(listOf<String?>("T31"), trips.chosenLines)
        compose.onNode(hasText("Only the T31; the map shows its route")).assertExists()
        compose.onNodeWithTag("trip_line_T31").assertIsSelected()
        assertTrue(!exists("trip_line_row_E21"))
        compose.onNode(hasText("Then the T31")).assertExists()

        compose.onNodeWithTag("trip_line_any").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.chosenLines.size == 2 }
        assertEquals(listOf("T31", null), trips.chosenLines)
        waitFor("trip_line_row_E21")
        // A row is a way to choose its line too
        compose.onNodeWithTag("trip_line_row_E21").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.chosenLines.size == 3 }
        assertEquals("E21", trips.chosenLines.last())
    }

    @Test
    fun underTheTitleWhenTheTripArrivesWhichALineChosenChangesAtOnce() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.awaited.value = listOf(
            com.yenreh.caligo.trips.AwaitedLine("E21", NOW + 6 * 60_000),
            com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 30 * 60_000),
        )
        show(took = null)
        waitFor("trip_running")
        fun shown() = checkNotNull(com.yenreh.caligo.trips.TripArrival.of(checkNotNull(trips.running.value), NOW, trips.awaited.value))
        val any = shown()
        compose.onNode(hasText("Arrive ~", substring = true)).assertExists()
        trips.chooseLine("T31")
        val t31 = shown()
        assertTrue(t31.at > any.at)
        compose.waitUntil(WAIT_TIMEOUT_MS) {
            compose.onAllNodes(hasText(clockText(t31.at, hours24 = false, spanish = false), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun asABusLeftThePanelAsksWithYesAndNo() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.running.value = checkNotNull(trips.running.value).fixed(
            com.yenreh.caligo.trips.JourneyFixtures.fixAt(com.yenreh.caligo.domain.GeoPoint(TripFixtures.out[0].latitude, TripFixtures.out[0].longitude), NOW),
        )
        trips.awaited.value = listOf(
            com.yenreh.caligo.trips.AwaitedLine("E21", NOW - 60_000, asking = true),
            com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 4 * 60_000),
        )
        show(took = null)
        waitFor("trip_asking")
        compose.onNode(hasText("Did you take the E21?")).assertExists()
        // Only the bus still coming has its time
        compose.onNode(hasText("T31 4${NBSP}min")).assertExists()
        // Yes says "I took the E21": only the other line keeps its button, under the hint
        assertTrue(!exists("trip_took_E21"))
        assertTrue(exists("trip_took_T31"))
        compose.onNode(hasText("As you board, tap the line you took")).assertExists()
        compose.assertControlsAccessible()
        compose.onNodeWithTag("trip_asking_no").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.notTaken.isNotEmpty() }
        assertEquals(listOf("E21"), trips.notTaken)
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists("trip_asking") }
    }

    @Test
    fun askingOnTheOnlyLineLeavesNoButtonNorHint() {
        // On foot near the A47's stop, a ride of one line, its bus just gone
        val journey = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 10 * 60_000)
            .took("T31", NOW - 8 * 60_000).copy(index = 2, ride = null, legStartedAt = NOW - 60_000)
        val board = (journey.legs[3] as com.yenreh.caligo.domain.JourneyLeg.Ride).ride.board
        val near = com.yenreh.caligo.trips.JourneyFixtures.east(com.yenreh.caligo.domain.GeoPoint(board.latitude, board.longitude), -100.0)
        trips.running.value = journey.fixed(com.yenreh.caligo.trips.JourneyFixtures.fixAt(near, NOW))
        trips.awaited.value = listOf(com.yenreh.caligo.trips.AwaitedLine("A47", NOW - 60_000, asking = true))
        show(took = null)
        waitFor("trip_asking")
        compose.onNode(hasText("Did you take the A47?")).assertExists()
        assertTrue(!exists("trip_took_A47"))
        compose.onNode(hasText("As you board, tap the line you took")).assertDoesNotExist()
    }

    @Test
    fun yesOnThePanelTakesThatLine() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.awaited.value = listOf(com.yenreh.caligo.trips.AwaitedLine("T31", NOW - 60_000, asking = true))
        show(took = null)
        waitFor("trip_asking")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_asking_yes"))
        compose.onNodeWithTag("trip_asking_yes").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.takenLines.isNotEmpty() }
        assertEquals(listOf("T31"), trips.takenLines)
    }

    @Test
    fun aRideFollowedWithNoStopHasNoArrival() {
        show()
        waitFor("trip_skip")
        compose.onNodeWithTag("trip_skip").performClick()
        waitFor("trip_running")
        assertTrue(compose.onAllNodes(hasText("Arrive", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aRideOfOneLineShowsItsTimesWithNoChoice() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        // On foot to the A47, after the first ride
        trips.running.value = checkNotNull(trips.running.value).took("T31", NOW).copy(index = 2, ride = null, legStartedAt = NOW)
        show(took = null)
        waitFor("trip_lines")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_lines"))
        assertTrue(!exists("trip_line_any"))
        compose.onNodeWithTag("trip_line_row_A47").assert(androidx.compose.ui.test.hasContentDescription("A47, No buses coming"))
    }

    @Test
    fun aPlannerRideGetsOffWhereItsPlanSaysWithNoStopToTap() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.running.value = trips.running.value!!.took("T31", NOW)
        show(took = null)
        waitFor("trip_running")
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_alight"))
        compose.onNodeWithTag("trip_alight").assertIsSelected()
        assertTrue(compose.onAllNodes(hasText("Tap a stop to get off there")).fetchSemanticsNodes().isEmpty())
        val stop = "trip_stop_${TripFixtures.out[3].stopId}"
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag(stop))
        compose.onNodeWithTag(stop).assertHasNoClickAction()
    }

    @Test
    fun atTheStopTheAwaitedBusesShowSoonestFirstEachWithItsButton() {
        trips.startJourney(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro")
        trips.running.value = trips.running.value!!.fixed(
            com.yenreh.caligo.trips.JourneyFixtures.fixAt(
                com.yenreh.caligo.domain.GeoPoint(TripFixtures.out[0].latitude, TripFixtures.out[0].longitude),
                NOW,
            ),
        )
        trips.awaited.value = listOf(
            com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 2 * 60_000),
            com.yenreh.caligo.trips.AwaitedLine("E21", NOW + 5 * 60_000),
        )
        show(took = null)
        waitFor("trip_awaited")
        compose.onNode(hasText("Take the T31 or E21")).assertExists()
        compose.onNode(hasText("T31 2${NBSP}min · E21 5${NBSP}min")).assertExists()
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_took_T31"))
        compose.onNodeWithTag("trip_took_T31").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.takenLines.isNotEmpty() }
        assertEquals(listOf("T31"), trips.takenLines)
        compose.assertControlsAccessible()
    }
}
