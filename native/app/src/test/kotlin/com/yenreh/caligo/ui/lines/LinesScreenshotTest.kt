package com.yenreh.caligo.ui.lines

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.TransitLine
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.AddStopScreen
import com.yenreh.caligo.ui.stops.AddStopTabs
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.time.Duration
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The line screens at Pixel size (1080 x 2400 px, 420 dpi), in Spanish,
 * light and dark, over fixture tiles: a line's route with its stops, its
 * buses and its hours, and the Lines tab of the add-stop screen. The dark
 * ones also ask for the dark map.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class LinesScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** 2026-09-29 at 21:30, local time */
    private val evening = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 21, 30)
    }.timeInMillis

    /** Out from Aguablanca to Andrés Sanín, and back */
    private val route = listOf(
        LineStop("504107", "Aguablanca B3", 3.4141, -76.4812, 0, 1),
        LineStop("514484", "Tv 103 con Kr 28F", 3.4109, -76.4798, 0, 2),
        LineStop("514490", "Kr 28F con Cl 96", 3.4135, -76.4870, 0, 3),
        LineStop("514495", "Cl 96 con Kr 26", 3.4170, -76.4905, 0, 4),
        LineStop("514500", "Kr 26 con Cl 86", 3.4205, -76.4930, 0, 5),
        LineStop("500300", "Estación Andrés Sanín", 3.4250, -76.4950, 0, 6),
        LineStop("500301", "Estación Andrés Sanín B", 3.4252, -76.4951, 1, 1),
        LineStop("514501", "Cl 86 con Kr 26", 3.4207, -76.4928, 1, 2),
        LineStop("504108", "Aguablanca B4", 3.4142, -76.4811, 1, 3),
    )

    private val repository = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf("A47" to route)
        buses = mapOf(
            "A47" to listOf(
                LineBus("32051", 3.4122, -76.4835, direction = 0),
                LineBus("12081", 3.4188, -76.4918, direction = 0),
                LineBus("11002", 3.4232, -76.4942, direction = null),
                LineBus("13004", 3.4160, -76.4860, direction = 1),
            ),
        )
        transitLines = listOf(
            TransitLine(302, "A02"),
            TransitLine(347, "A47"),
            TransitLine(312, "A12A"),
            TransitLine(0, "A52"),
            TransitLine(121, "E21"),
            TransitLine(127, "P27D"),
            TransitLine(131, "T31"),
            TransitLine(150, "T50"),
        ).sortedBy { it.name }
        hours = mapOf(
            "A02" to LineHours(5.hours, 21.hours),
            "A47" to LineHours(4.hours + 30.minutes, 22.hours + 40.minutes),
            "A12A" to LineHours(4.hours + 23.minutes, 19.minutes),
            "E21" to LineHours(5.hours, 20.hours + 55.minutes),
            "P27D" to LineHours(5.hours + 30.minutes, 21.hours + 30.minutes),
            "T31" to LineHours(4.hours + 30.minutes, 23.hours),
            "T50" to LineHours(4.hours + 45.minutes, 23.hours + 15.minutes),
        )
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { exists(matcher) }

    /**
     * Until the tiles of [zoom] have come, and a moment for them to be
     * drawn. The map paces its work with delays on the main thread, whose
     * clock moves here only when told.
     */
    private fun waitForTiles(tiles: FixtureTiles, zoom: Int) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            tiles.asked.count { it.startsWith("$zoom/") } >= 6
        }
        repeat(5) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
        }
    }

    // The line, with its route, buses and hours

    private fun line(name: String, dark: Boolean) {
        val model = LineViewModel("A47", repository)
        val tiles = FixtureTiles(dark = dark)
        compose.setContent {
            CaliGoTheme {
                LineScreen(
                    model,
                    hours = repository.hours["A47"],
                    tiles = remember { tiles },
                    darkTiles = dark,
                    carto = true,
                    onBack = {},
                    shown = true,
                    now = { evening },
                    fadeIn = false,
                )
            }
        }
        waitFor(hasTestTag("bus_12081"))
        waitForTiles(tiles, zoom = model.map!!.camera.zoom.roundToInt())
        compose.captureWindow(name)
        compose.assertControlsAccessible()
    }

    @Test
    fun lineLight() = line("line_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun lineDark() = line("line_dark", dark = true)

    // At font scale 2.0, the largest Android offers: nothing clipped or overlapping

    @Test
    @Config(fontScale = 2f)
    fun lineFontScale2() = line("line_font2", dark = false)

    @Test
    @Config(fontScale = 2f)
    fun linesTabFontScale2() = linesTab("lines_tab_font2", dark = false)

    // The Lines tab of the add-stop screen

    private fun linesTab(name: String, dark: Boolean) {
        val stops = StopsViewModel(
            repository,
            location = FakeLocationSource(),
            settings = MutableStateFlow(Settings(isLoading = false, darkMap = dark)),
        )
        val lines = LinesViewModel(repository)
        compose.setContent {
            CaliGoTheme {
                AddStopScreen(stops, lines, onBack = {}, onStation = {}, initialTab = AddStopTabs.LINES, now = { evening })
            }
        }
        waitFor(hasText("04:23 – 00:19"))
        compose.captureWindow(name)
        compose.assertControlsAccessible()
    }

    @Test
    fun linesTabLight() = linesTab("lines_tab_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun linesTabDark() = linesTab("lines_tab_dark", dark = true)
}
