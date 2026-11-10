package com.yenreh.caligo.ui.trips

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val viewModel = TripViewModel(took, repository, trips, waits, location, notifications)
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
        compose.onNode(hasText("~1 min")).assertExists()
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
        compose.onNode(hasText("~10 min · 7 stops")).assertExists()
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
    }

    @Test
    fun finishEndsTheTripAndCloses() {
        show()
        waitFor("trip_choose")
        choose(TripFixtures.out[7].stopId)
        waitFor("trip_finish")
        compose.onNodeWithTag("trip_finish").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { trips.finished == 1 }
        assertEquals(1, closed)
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
        val ended = com.yenreh.caligo.domain.Trip.start(TripFixtures.plan, NOW)
        trips.ended.value = com.yenreh.caligo.trips.EndedTrip(ended, com.yenreh.caligo.domain.TripEnding.REACHED)
        show(took = null)
        waitFor("trip_ended")
        compose.onNode(hasText("You reached Pampalinda.")).assertExists()
    }
}
