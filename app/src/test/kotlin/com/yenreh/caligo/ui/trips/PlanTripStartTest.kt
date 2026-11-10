package com.yenreh.caligo.ui.trips

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.trips.FakeTripControl
import com.yenreh.caligo.trips.TripStart
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.FakeNotificationAccess
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Start trip" on the planner's open option: the whole trip followed, after what it needs */
@RunWith(AndroidJUnit4::class)
class PlanTripStartTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixtures = TripFixtures()
    private val trips = FakeTripControl()
    private var opened = 0

    @After
    fun clear() = fixtures.clear()

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(notifications: FakeNotificationAccess): PlanTripViewModel {
        val model = fixtures.planner(trips = trips, notifications = notifications)
        compose.setContent {
            CaliGoTheme {
                PlanTripScreen(
                    model,
                    tiles = remember { FixtureTiles() },
                    darkTiles = false,
                    carto = true,
                    onBack = {},
                    onChoose = {},
                    shown = true,
                    now = { fixtures.clock },
                    fadeIn = false,
                    onTripStarted = { opened++ },
                )
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasTestTag("option_0")) }
        compose.onNodeWithTag("option_0").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasTestTag("start_trip")) }
        compose.onNodeWithTag("start_trip").performScrollTo().performClick()
        return model
    }

    @Test
    fun startTripFollowsTheOpenOptionAndOpensTheTrip() {
        val model = show(FakeNotificationAccess(granted = true))
        compose.waitUntil(WAIT_TIMEOUT_MS) { opened == 1 }
        val start = trips.starts.single() as TripStart.Planned
        assertEquals(model.screen.value.open?.rides?.map { it.line }, start.option.rides.map { it.line })
        assertEquals("Destination", start.destination)
        assertTrue(trips.running.value?.planned == true)
    }

    @Test
    fun withoutNotificationsNoTripStartsAndItSaysWhy() {
        show(FakeNotificationAccess(granted = false, answer = false))
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasTestTag("trip_message")) }
        assertTrue(trips.starts.isEmpty())
        assertEquals(0, opened)
    }
}
