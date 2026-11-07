package com.yenreh.caligo.ui.trips

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The place picker's _MapPointPicker: where it opens, the point it hands over, and where it was left kept */
@RunWith(AndroidJUnit4::class)
class MapPointPickerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val used = mutableListOf<GeoPoint>()
    private var backs = 0

    private val content: @androidx.compose.runtime.Composable (TripPlace?) -> Unit = { near ->
        CaliGoTheme {
            MapPointPicker(
                near = near,
                tiles = remember { FixtureTiles() },
                darkTiles = false,
                carto = true,
                onBack = { backs++ },
                onUse = { used.add(it) },
                fadeIn = false,
            )
        }
    }

    @Test
    fun opensOnThePlaceItIsNearAndHandsOverThePointUnderTheCrosshair() {
        val near = TripPlace(3.4200, -76.5400, "Home")
        compose.setContent { content(near) }
        compose.waitForIdle()

        compose.onNodeWithText("Pick on the map").assertExists()
        compose.onNodeWithText("Use this point").performClick()

        assertEquals(listOf(GeoPoint(3.4200, -76.5400)), used)
    }

    @Test
    fun withNowhereToStartOpensOnCalisCentre() {
        compose.setContent { content(null) }
        compose.waitForIdle()

        compose.onNodeWithTag("use_point").performClick()

        assertEquals(listOf(GeoPoint(3.4516, -76.5320)), used)
    }

    @Test
    fun theMapMovedHandsOverTheNewPointAndKeepsItThroughARestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { content(TripPlace(3.4200, -76.5400, "Home")) }
        compose.waitForIdle()

        // Slowly, so nothing flings
        compose.onNodeWithTag("map").performTouchInput {
            swipe(start = center, end = center - Offset(200f, 0f), durationMillis = 2_000)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("use_point").performClick()
        val moved = used.single()
        assertNotEquals(-76.5400, moved.longitude, 1e-6)
        // Dragged left, the map shows what lies east
        assertTrue(moved.longitude > -76.5400)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("use_point").performClick()

        assertEquals(moved.latitude, used.last().latitude, 1e-9)
        assertEquals(moved.longitude, used.last().longitude, 1e-9)
    }

    @Test
    fun theArrowGoesBack() {
        compose.setContent { content(null) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
