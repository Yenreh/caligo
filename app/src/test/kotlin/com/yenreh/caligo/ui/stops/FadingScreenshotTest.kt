package com.yenreh.caligo.ui.stops

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.captureScreen
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.common.MovingClock
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Arrivals that fade out, at Pixel size in Spanish, light and dark: a
 * favorite's card and a stop's details with a bus at the stop and one
 * passed, under build/outputs/roborazzi/fading_*.png
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class FadingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = HomeSamples.NOW

    private fun bus(line: String, destination: String, seconds: Int, vehicle: String) = BusArrival(
        line = line,
        destination = destination,
        arrivalTime = now + seconds * 1000L,
        vehicleId = vehicle,
    )

    /** Reaches the stop 20 s from now, gone from the last answer */
    private val reaching = bus("E21", "Terminal Paso del Comercio", 20, "e")

    /** Passed 70 s ago, gone from the last answer */
    private val passed = bus("T31", "Universidades", -70, "p")

    private val coming = listOf(bus("A47", "Valle Grande", 240, "a"), bus("T31", "Universidades", 540, "t"))

    /** Two answers, a minute and ten seconds ago; the last no longer lists the first two */
    private val fading = FadingArrivals.EMPTY
        .fed("500800", listOf(passed, reaching) + coming, now - 60_000)
        .fed("500800", coming, now - 10_000)

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // A favorite's card, on the stops screen

    private fun stops(name: String) {
        val state = HomeSamples.stopsState.copy(
            arrivals = HomeSamples.arrivals + ("500800" to coming),
            fading = mapOf("500800" to fading),
        )
        compose.setContentAt(now) { CaliGoTheme { StopsScreen(state, sortedByProximity = false) } }
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("En la parada")) }
        compose.captureWindow(name)
    }

    @Test
    fun cardLight() = stops("fading_card_light")

    @Test
    @Config(qualifiers = "+night")
    fun cardDark() = stops("fading_card_dark")

    @Test
    @Config(fontScale = 2f)
    fun cardFontScale2() {
        stops("fading_card_font2")
        assertFits()
    }

    // A stop's details: opened with the first answer, refreshed with the second

    private fun details(name: String) {
        val clock = MovingClock(now - 60_000)
        val stop = NearbyStop("500800", "Plaza de Cayzedo A1", 119.5, listOf(passed, reaching) + coming)
        compose.setContent {
            AtTime(clock) {
                CaliGoTheme {
                    StopDetailsSheet(
                        stop = stop,
                        isSaved = true,
                        actions = StopDetailsActions(
                            refresh = { it.copy(arrivals = coming) },
                            loadLines = { emptyList() },
                            save = { FavoriteSave.Saved },
                        ),
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasTestTag("stop_details")) }
        clock.advance(50_000)
        compose.onNode(hasContentDescription("Actualizar llegadas")).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("En la parada")) }
        clock.advance(10_000)
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("Pasó hace 1 min")) }
        compose.captureScreen(name)
    }

    @Test
    fun detailsLight() = details("fading_details_light")

    @Test
    @Config(qualifiers = "+night")
    fun detailsDark() = details("fading_details_dark")

    @Test
    @Config(fontScale = 2f)
    fun detailsFontScale2() {
        details("fading_details_font2")
        assertFits()
    }

    /**
     * The longer labels fit whole, and leave the destination beside them
     * room to read
     */
    private fun assertFits() {
        for (label in listOf("En la parada", "Pasó hace 1 min")) {
            val layout = layoutOf(label)
            assertFalse("$label clipped", layout.hasVisualOverflow)
            assertFalse("$label cut short", layout.isLineEllipsized(layout.lineCount - 1))
        }
        val density = compose.density
        for (destination in listOf("Terminal Paso del Comercio", "Universidades")) {
            val node = compose.onAllNodes(hasText(destination), useUnmergedTree = true).fetchSemanticsNodes().first()
            val width = with(density) { node.size.width.toDp() }
            assertTrue("$destination squeezed to $width", width >= 100.dp)
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }
}
