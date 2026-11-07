package com.yenreh.caligo.ui.navigation

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** An activity that shows [PlannerApp] each time it is made, as MainActivity shows the app */
class PlannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shown = checkNotNull(app) { "No app to show" }
        shown.activityModels(this)
        setContent { shown.Content() }
    }

    companion object {
        var app: PlannerApp? = null
    }
}

/**
 * The planner and its picker through a real rotation: the activity made
 * again in landscape, its view models and each screen's kept, as Android
 * keeps them through a configuration change
 */
@RunWith(AndroidJUnit4::class)
class PlanTripRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = PlannerApp()
    private lateinit var scenario: ActivityScenario<PlannerActivity>

    @Before
    fun launch() {
        val context = ApplicationProvider.getApplicationContext<CaliGoApp>()
        // Declared here, as the app's manifest is the only one Robolectric reads
        shadowOf(context.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = PlannerActivity::class.java.name
                packageName = context.packageName
            },
        )
        PlannerActivity.app = app
        scenario = ActivityScenario.launch(PlannerActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun close() {
        scenario.close()
        PlannerActivity.app = null
        app.clear()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private fun rotate() {
        RuntimeEnvironment.setQualifiers("+land")
        scenario.recreate()
        compose.waitForIdle()
    }

    private fun activity(): ComponentActivity {
        var current: ComponentActivity? = null
        scenario.onActivity { current = it }
        return checkNotNull(current)
    }

    /** The stop a 12 in the picker's list, not the favorite on it */
    private val stopTile = hasTestTag("place_tile") and hasText("a 12") and !hasText("Trabajo")

    @Test
    fun theOpenOptionKeepsItsStepsAndItsMapWhereItWasLeft() {
        val before = activity()
        compose.onNode(hasContentDescription("Plan a trip")).performClick()
        waitFor(hasTestTag("origin_field"))
        compose.onNodeWithTag("destination_field").performClick()
        waitFor(hasTestTag("place_search"))
        compose.onNodeWithTag("place_search").performTextReplacement("a 12")
        compose.onNodeWithTag("places").performScrollToNode(stopTile)
        compose.onNode(stopTile).performClick()
        waitFor(hasTestTag("option_0"))
        compose.onNodeWithTag("option_0").performClick()
        waitFor(hasTestTag("map"))
        val planner = app.planners.single().first
        val map = planner.mapFor(DpSize.Zero)

        rotate()

        assertNotSame(before, activity())
        waitFor(hasTestTag("map"))
        compose.onNodeWithTag("steps").assertExists()
        compose.onNodeWithTag("destination_field").assert(hasText("a 12"))
        // The same planner, and the same map: it opens where it was left
        assertSame(planner, app.planners.single().first)
        assertSame(map, planner.mapFor(DpSize.Zero))
    }

    @Test
    fun thePickerKeepsWhatWasTypedAndHandsThePlaceBackAfterwards() {
        compose.onNode(hasContentDescription("Plan a trip")).performClick()
        waitFor(hasTestTag("origin_field"))
        compose.onNodeWithTag("destination_field").performClick()
        waitFor(hasTestTag("place_search"))
        compose.onNodeWithTag("place_search").performTextReplacement("a 12")
        val picker = app.pickers.single()

        rotate()

        waitFor(hasTestTag("place_search"))
        compose.onNodeWithText("Where to?").assertExists()
        compose.onNodeWithTag("place_search").assert(hasText("a 12"))
        assertSame(picker, app.pickers.single())
        compose.onNodeWithTag("places").performScrollToNode(stopTile)
        compose.onNode(stopTile).performClick()

        waitFor(hasTestTag("origin_field"))
        compose.onNodeWithTag("destination_field").assert(hasText("a 12"))
        assertEquals(1, app.planners.size)
    }

    @Test
    fun theMapPointPickerKeepsItsPlaceAndHandsThePointBack() {
        compose.onNode(hasContentDescription("Plan a trip")).performClick()
        waitFor(hasTestTag("origin_field"))
        compose.onNodeWithTag("origin_field").performClick()
        waitFor(hasTestTag("pick_on_map"))
        compose.onNodeWithTag("pick_on_map").performClick()
        waitFor(hasTestTag("use_point"))

        rotate()

        waitFor(hasTestTag("use_point"))
        compose.onNodeWithTag("use_point").performClick()

        waitFor(hasTestTag("origin_field"))
        compose.onNodeWithTag("origin_field").assert(hasText("Near a 0"))
        assertEquals(1, app.pickers.size)
        assertEquals(1, app.planners.size)
    }
}
