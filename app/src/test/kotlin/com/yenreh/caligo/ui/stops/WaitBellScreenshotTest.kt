package com.yenreh.caligo.ui.stops

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.captureScreen
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.RunningWait
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The bells of a favorite's card and of a stop's details, one bus waited
 * for, at Pixel size in Spanish, light and dark and at font scale 2,
 * under build/outputs/roborazzi/wait_bell_*.png
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class WaitBellScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = HomeSamples.NOW

    private fun bus(line: String, destination: String, seconds: Int, vehicle: String) =
        BusArrival(line, destination, now + seconds * 1000L, vehicle, stopName = "Plaza de Cayzedo A1")

    private val passed = bus("T31", "Universidades", -70, "p")
    private val reaching = bus("E21", "Terminal Paso del Comercio", 20, "e")
    private val coming = listOf(bus("A47", "Valle Grande", 240, "a"), bus("T31", "Universidades", 540, "t"))
    private val fading = FadingArrivals.EMPTY
        .fed("500800", listOf(passed, reaching) + coming, now - 60_000)
        .fed("500800", coming, now - 10_000)

    /** The T31 nine minutes away is waited for */
    private val control = FakeWaitControl().apply {
        running.value = listOf(RunningWait(WaitKey("500800", "t", now + 540_000), now + 540_000, now - 10_000))
    }
    private val access = FakeNotificationAccess(granted = true)

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(content: @Composable () -> Unit) {
        compose.setContentAt(now) { CaliGoTheme { WaitBellsHost(control, access, content) } }
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasTestTag("wait_bell_on")) }
    }

    private fun card(name: String) {
        val stop = HomeSamples.stops[0].copy(lines = emptyList())
        show { FavoriteStopCard(stop = stop, arrivals = coming, fading = fading) }
        compose.captureWindow(name)
    }

    @Test
    fun cardLight() = card("wait_bell_card_light")

    @Test
    @Config(qualifiers = "+night")
    fun cardDark() = card("wait_bell_card_dark")

    @Test
    @Config(fontScale = 2f)
    fun cardFontScale2() {
        card("wait_bell_card_font2")
        assertFits("Terminal Paso del Comercio")
    }

    private fun details(name: String) {
        val stop = NearbyStop("500800", "Plaza de Cayzedo A1", 119.5, coming)
        show {
            StopDetailsContent(
                initial = stop,
                isSaved = true,
                lines = com.yenreh.caligo.ui.common.Loaded.Ready(emptyList<com.yenreh.caligo.domain.entities.StopLine>()),
                actions = StopDetailsActions(refresh = { it }, loadLines = { emptyList() }, save = { FavoriteSave.Saved }),
                onDismiss = {},
            )
        }
        compose.captureScreen(name)
    }

    @Test
    fun detailsLight() = details("wait_bell_details_light")

    @Test
    @Config(qualifiers = "+night")
    fun detailsDark() = details("wait_bell_details_dark")

    @Test
    @Config(fontScale = 2f)
    fun detailsFontScale2() {
        details("wait_bell_details_font2")
        assertFits()
    }

    /** Each bell keeps its 48 dp inside the screen, and the destinations beside them room to read */
    private fun assertFits(vararg more: String) {
        val density = compose.density
        val width = with(density) { compose.onRoot().fetchSemanticsNode().size.width.toDp() }
        for (bell in compose.onAllNodes(hasTestTag("wait_bell") or hasTestTag("wait_bell_on"), useUnmergedTree = true).fetchSemanticsNodes()) {
            val size = with(density) { bell.size.width.toDp() }
            val right = with(density) { bell.boundsInRoot.right.toDp() }
            assertTrue("bell $size wide", size >= 48.dp)
            assertTrue("bell ends at $right of $width", right <= width)
        }
        for (destination in listOf("Valle Grande", "Universidades") + more) {
            val node = compose.onAllNodes(hasText(destination), useUnmergedTree = true).fetchSemanticsNodes().first()
            val room = with(density) { node.size.width.toDp() }
            assertTrue("$destination squeezed to $room", room >= 100.dp)
        }
    }
}
