package com.yenreh.caligo.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.TripPlace
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trip planner as the app opens it, from the home's bar and from a
 * stop's "Go here", and the place picker's round trip back to it: a stop
 * found by text, a point on the map, the device and a favorite. The
 * activity's stops and lines go first, over a fake repository; the
 * planner and the picker are made over the same one, sharing one route
 * network as the app's container does.
 */
@RunWith(AndroidJUnit4::class)
class PlanTripNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = PlannerApp()
    private val trips get() = app.trips
    private val station get() = app.station
    private val location get() = app.location
    private val planners get() = app.planners
    private val pickers get() = app.pickers

    @After
    fun clear() = app.clear()

    @Composable
    private fun App(backStack: List<NavKey>) = app.Content(backStack)

    private fun activityModels(nearby: List<NearbyStop> = emptyList()) = app.activityModels(compose.activity, nearby)

    private fun show(vararg backStack: NavKey = arrayOf(Route.Home), nearby: List<NearbyStop> = emptyList()) {
        activityModels(nearby)
        compose.setContent { App(backStack.toList()) }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(matcher) }

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private val home = hasContentDescription("Settings")

    /** The stop a 12 in the picker's list, not the favorite on it */
    private val stopTile = hasTestTag("place_tile") and hasText("a 12") and !hasText("Trabajo")
    private val planner = hasTestTag("origin_field")
    private val picker = hasTestTag("place_search")

    private fun openPlanner() {
        compose.onNodeWithContentDescription("Plan a trip").performClick()
        waitFor(planner)
        waitGone(home)
    }

    private fun openPicker(origin: Boolean) {
        compose.onNodeWithTag(if (origin) "origin_field" else "destination_field").performClick()
        waitFor(picker)
        waitGone(planner)
    }

    // Opening and closing

    @Test
    fun theBarsPlanButtonOpensThePlannerAndBackComesHome() {
        show()

        openPlanner()

        compose.onNodeWithText("Plan a trip").assertExists()
        // From where the device was last seen, nowhere to go yet
        compose.onNodeWithTag("origin_field").assert(hasText("My location"))
        compose.onNodeWithTag("destination_field").assert(hasText("Where to?"))
        assertEquals(null, planners.single().second)

        systemBack()
        waitFor(home)
        waitGone(planner)
    }

    @Test
    fun thePlannersArrowComesHomeAndLetsItsViewModelGo() {
        show()
        openPlanner()

        compose.onNodeWithContentDescription("Back").performClick()

        waitFor(home)
        // The screen went with its view model: nothing left asking
        compose.waitUntil(WAIT_TIMEOUT_MS) { !planners.single().first.viewModelScope.isActive }
    }

    @Test
    fun goHereFromAStopsDetailsPlansTheTripThereAndBackReturns() {
        val stop = NearbyStop("a12", "a 12", 30.0, latitude = trips.to.latitude, longitude = trips.to.longitude)
        show(Route.Home, station, nearby = listOf(stop))
        waitFor(hasText("a 12"))

        compose.onNodeWithText("a 12").performClick()
        waitFor(hasTestTag("go_here"))
        compose.onNodeWithTag("go_here").performScrollTo().performClick()

        waitFor(planner)
        compose.onNodeWithTag("destination_field").assert(hasText("a 12"))
        assertEquals(TripPlace(trips.to.latitude, trips.to.longitude, "a 12"), planners.single().second)
        waitFor(hasTestTag("option_0"))

        // Back to the station, the sheet put away
        systemBack()
        waitGone(planner)
        waitFor(hasText(station.name))
        waitGone(hasTestTag("go_here"))
    }

    // The place picker's round trip

    @Test
    fun aStopFoundByTextIsTakenAsTheDestination() {
        show()
        openPlanner()

        openPicker(origin = false)
        compose.onNodeWithText("Where to?").assertExists()
        compose.onNodeWithTag("place_search").performTextReplacement("a 12")
        compose.onNodeWithTag("places").performScrollToNode(stopTile)
        compose.onNode(stopTile).performClick()

        waitFor(planner)
        waitGone(picker)
        compose.onNodeWithTag("destination_field").assert(hasText("a 12"))
        waitFor(hasTestTag("option_0"))
        assertEquals(1, planners.size)
    }

    @Test
    fun aPointOnTheMapIsTakenNamedByTheStopNearIt() {
        show()
        openPlanner()
        openPicker(origin = true)
        compose.onNodeWithText("Where from?").assertExists()

        compose.onNodeWithTag("pick_on_map").performClick()
        waitFor(hasTestTag("use_point"))
        waitGone(picker)
        // Opened where the origin is, the device's last fix, 220 m from a 0
        compose.onNodeWithTag("use_point").performClick()

        waitFor(planner)
        waitGone(hasTestTag("use_point"))
        compose.onNodeWithTag("origin_field").assert(hasText("Near a 0"))
        assertEquals(TripPlace(trips.from.latitude, trips.from.longitude, "Near a 0"), planners.single().first.screen.value.from)
        // Both pickers gone: back leaves the planner
        systemBack()
        waitFor(home)
    }

    @Test
    fun myLocationIsTakenAsTheOrigin() {
        show()
        openPlanner()
        compose.runOnUiThread { planners.single().first.choose(origin = true, place = trips.place(3.0, 0.5, "Home")) }
        compose.onNodeWithTag("origin_field").assert(hasText("Home"))
        openPicker(origin = true)

        compose.onNodeWithTag("use_device").performClick()

        waitFor(planner)
        compose.onNodeWithTag("origin_field").assert(hasText("My location"))
        val from = planners.single().first.screen.value.from!!
        assertEquals(TripPlace(trips.from.latitude, trips.from.longitude, "My location", isDevice = true), from)
    }

    @Test
    fun aFavoriteIsTakenAsTheDestinationWhereItsStopIs() {
        show()
        openPlanner()
        openPicker(origin = false)

        compose.onNodeWithText("Trabajo").performClick()

        waitFor(planner)
        compose.onNodeWithTag("destination_field").assert(hasText("Trabajo"))
        val stop = trips.line("a", 12..12).single()
        assertEquals(
            TripPlace(stop.latitude, stop.longitude, "Trabajo", kind = PlaceKind.STOP, detail = "a 12", stopId = "a12"),
            planners.single().first.screen.value.to,
        )
    }

    @Test
    fun backFromThePickerLeavesThePlannerAsItWas() {
        show()
        openPlanner()
        compose.runOnUiThread { planners.single().first.choose(origin = false, place = trips.to) }
        openPicker(origin = false)

        systemBack()

        waitFor(planner)
        compose.onNodeWithTag("destination_field").assert(hasText("Destination"))
        assertEquals(1, pickers.size)
        // A new picker each time, its own view model
        openPicker(origin = true)
        compose.onNodeWithText("Where from?").assertExists()
        assertEquals(2, pickers.size)
    }

    // A saved state restored, as after the process died: the back stack,
    // what each screen saved and a choice still on its way come back; the
    // screens' view models are made anew

    @Test
    fun afterARestoreThePickerKeepsWhatWasTypedAndHandsThePlaceToANewPlanner() {
        activityModels()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { App(listOf(Route.Home)) }
        compose.waitForIdle()
        openPlanner()
        openPicker(origin = false)
        compose.onNodeWithTag("place_search").performTextReplacement("a 12")

        restoration.emulateSavedInstanceStateRestore()

        waitFor(picker)
        compose.onNodeWithTag("place_search").assert(hasText("a 12"))
        compose.onNodeWithText("Where to?").assertExists()
        assertEquals(2, pickers.size)
        compose.onNodeWithTag("places").performScrollToNode(stopTile)
        compose.onNode(stopTile).performClick()

        waitFor(planner)
        compose.onNodeWithTag("destination_field").assert(hasText("a 12"))
        val (restored, destination) = planners.last()
        assertEquals(null, destination)
        assertEquals("a 12", restored.screen.value.to!!.name)
        waitFor(hasTestTag("option_0"))
    }

    @Test
    fun afterARestoreThePlannerStillGoesWhereItsKeySays() {
        activityModels()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { App(listOf(Route.Home, Route.PlanTrip(trips.to.key))) }
        compose.waitForIdle()
        waitFor(hasTestTag("option_0"))

        restoration.emulateSavedInstanceStateRestore()

        waitFor(hasTestTag("option_0"))
        compose.onNodeWithTag("destination_field").assert(hasText("Destination"))
        assertEquals(listOf(trips.to, trips.to), planners.map { it.second })
        // Back still goes home from the restored stack
        systemBack()
        waitFor(home)
    }
}
