package com.yenreh.caligo.ui.stops

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The map at Pixel size (1080 x 2400 px, 420 dpi), in Spanish, light and
 * dark, over fixture tiles drawn in the tiles' own colours: the map
 * screen with the stops found and one picked, and the add-stop screen
 * opening on its Map tab. The dark ones also ask for the dark map.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class MapStopsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The moment the buses are timed from, taken once; the screens' clock stops here */
    private val now = System.currentTimeMillis()

    private fun bus(line: String, minutes: Int) =
        BusArrival(line = line, destination = "", arrivalTime = now + minutes * 60_000L, vehicleId = line)

    private val cali = GeoPoint(3.4516, -76.5320)

    private val around = listOf(
        NearbyStop("500800", "Plaza de Cayzedo A1", 119.5, listOf(bus("E21", 2), bus("A47", 4), bus("T31", 9))),
        NearbyStop("500751", "La Ermita A2", 187.5, listOf(bus("P27D", 6))),
        NearbyStop("500802", "Cl 12 con Kr 5", 262.0),
    )

    private fun repository() = FakeStopsRepository(emptyList(), around).apply {
        located = mapOf(
            "500800" to GeoPoint(3.4508, -76.5326),
            "500751" to GeoPoint(3.4529, -76.5311),
        )
    }

    private fun mapViewModel() =
        MapStopsViewModel(repository(), FakeLocationSource(), MapStopsViewModelTest.MemoryJsonCache(), CoroutineScope(Dispatchers.Unconfined))

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    /**
     * Until the tiles of [zoom] have come, and a moment for them to be
     * drawn. The map paces its work with delays on the main thread, whose
     * clock moves here only when told.
     */
    private fun waitForTiles(tiles: FixtureTiles, zoom: Int) {
        compose.waitUntil(WAIT_TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            tiles.asked.count { it.startsWith("$zoom/") } >= 6
        }
        repeat(5) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
        }
    }

    // The map screen, stops found and one picked

    private fun picked(name: String, dark: Boolean) {
        val model = mapViewModel()
        val tiles = FixtureTiles(dark = dark)
        compose.setContentAt(now) {
            CaliGoTheme {
                // On the add screen's paper, as the tab shows it
                Box(Modifier.background(LabTheme.palette.bg)) {
                    MapStopsScreen(model, remember { tiles }, darkTiles = dark, carto = true, onOpen = {}, fadeIn = false)
                }
            }
        }
        waitFor(hasTestTag("map"))
        compose.onNode(hasContentDescription("Buscar aquí")).performClick()
        waitFor(hasTestTag("marker_500751"))
        compose.runOnUiThread { model.focus(model.screen.value.results!!.valueOrNull!![1]) }
        waitForTiles(tiles, zoom = 17)
        compose.captureWindow(name)
    }

    @Test
    fun pickedLight() = picked("map_stops_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun pickedDark() = picked("map_stops_dark", dark = true)

    // The add-stop screen, opening on the map

    private fun addStop(name: String, dark: Boolean) {
        val stops = StopsViewModel(
            repository(),
            location = FakeLocationSource(),
            settings = MutableStateFlow(Settings(isLoading = false, darkMap = dark)),
        )
        val model = mapViewModel()
        val tiles = FixtureTiles(dark = dark)
        val linesModel = LinesViewModel(FakeStopsRepository(emptyList(), emptyList()))
        compose.setContentAt(now) {
            CaliGoTheme {
                AddStopScreen(stops, linesModel, onBack = {}, onStation = {}, map = { onOpen ->
                    MapStopsScreen(model, remember { tiles }, darkTiles = dark, carto = true, onOpen = onOpen, fadeIn = false)
                })
            }
        }
        waitFor(hasText("Mueve el mapa y busca para ver paradas en cualquier lugar"))
        waitForTiles(tiles, zoom = 15)
        compose.captureWindow(name)
    }

    @Test
    fun addStopMapLight() = addStop("add_stop_map_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun addStopMapDark() = addStop("add_stop_map_dark", dark = true)

    // The credits open

    @Test
    fun creditsLight() {
        val model = mapViewModel()
        val tiles = FixtureTiles()
        compose.setContentAt(now) {
            CaliGoTheme {
                Box(Modifier.background(LabTheme.palette.bg)) {
                    MapStopsScreen(model, remember { tiles }, darkTiles = false, carto = true, onOpen = {}, fadeIn = false)
                }
            }
        }
        waitFor(hasTestTag("map"))
        compose.onNode(hasContentDescription("Créditos del mapa")).performClick()
        waitFor(hasTestTag("map_credits"))
        waitForTiles(tiles, zoom = 15)
        compose.captureWindow("map_credits_light")
    }
}
