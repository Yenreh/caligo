package com.yenreh.caligo.ui.trips

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.GeocodedPlace
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.time.Duration
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The trip planner and its pickers at Pixel size (1080 x 2400 px, 420
 * dpi), in Spanish, light and dark, over fixture tiles: options with one
 * open on the map, the hint, no trip found, the place picker with what it
 * finds, and the map point picker. The dark ones also ask for the dark
 * map.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class PlanTripScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val trips = TripFixtures(
        deviceName = "Mi ubicación",
        names = PlaceNames("Mi ubicación", "Punto en el mapa") { "Cerca de $it" },
    )

    @After
    fun clear() = trips.clear()

    /** T31 along the trunk line, E31 the same way, and the A14 feeder a block north */
    private val trunk = listOf(
        "Chiminangos", "Flora Industrial", "Salomia", "Popular", "Manzanares", "Fátima", "Piloto",
        "San Nicolás", "Ermita", "Centro", "San Bosco", "Estadio", "Universidades",
    )
    private val feeder = (0..12).map { "Cl ${70 - it * 5} con Kr 1" }

    private fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply {
        val t31 = trips.line("t", 0..12, names = trunk)
        routes = mapOf(
            "T31" to t31,
            "E31" to t31.map { it.copy(direction = 1) },
            "A14" to trips.line("f", 0..12, y = 1.0, names = feeder),
        )
        tripArrivals = {
            trips.arrivals(
                "t0" to listOf(trips.bus("T31", "11203", 14), trips.bus("E31", "12051", 22)),
                "t12" to listOf(trips.bus("T31", "11203", 39)),
            )
        }
    }

    private val destination get() = trips.place(12.0, 0.5, "Estación Universidades")

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Until the tiles of [zoom] have come, and a moment for them to be
     * drawn. The map paces its work with delays on the main thread, whose
     * clock moves here only when told.
     */
    private fun waitForTiles(tiles: FixtureTiles, zoom: Int) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            tiles.asked.count { it.startsWith("$zoom/") } >= 4
        }
        repeat(5) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
        }
    }

    private fun planner(model: PlanTripViewModel, tiles: FixtureTiles, dark: Boolean) {
        compose.setContent {
            CaliGoTheme {
                PlanTripScreen(
                    model,
                    tiles = remember { tiles },
                    darkTiles = dark,
                    carto = true,
                    onBack = {},
                    onChoose = {},
                    shown = true,
                    now = { trips.clock },
                    fadeIn = false,
                )
            }
        }
        compose.waitForIdle()
    }

    // The planner, its first option open on the map

    private fun open(name: String, dark: Boolean) {
        // Both places a walk off the line, so the dotted walks show
        val model = trips.planner(
            repository(),
            location = trips.location(lastKnown = trips.point(-1.0, -0.5)),
            destination = trips.place(13.0, -0.5, "Estación Universidades"),
        )
        val tiles = FixtureTiles(dark = dark)
        planner(model, tiles, dark)
        compose.onNodeWithTag("option_${model.screen.value.order.first()}").performClick()
        compose.waitUntil(5_000) { exists(hasTestTag("map_to")) }
        waitForTiles(tiles, zoom = model.mapFor(DpSize.Zero)!!.camera.zoom.roundToInt())
        compose.captureWindow(name)
    }

    @Test
    fun openLight() = open("plan_trip_open_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun openDark() = open("plan_trip_open_dark", dark = true)

    // The options, none open

    private fun options(name: String) {
        val model = trips.planner(repository(), destination = destination)
        planner(model, FixtureTiles(), dark = false)
        compose.captureWindow(name)
    }

    @Test
    fun optionsLight() = options("plan_trip_options_light")

    @Test
    @Config(qualifiers = "+night")
    fun optionsDark() = options("plan_trip_options_dark")

    // The hint, before a place to go

    private fun hint(name: String) {
        planner(trips.planner(repository(), destination = null), FixtureTiles(), dark = false)
        compose.captureWindow(name)
    }

    @Test
    fun hintLight() = hint("plan_trip_hint_light")

    @Test
    @Config(qualifiers = "+night")
    fun hintDark() = hint("plan_trip_hint_dark")

    // No trip found: the lines only run east

    private fun noTrip(name: String) {
        val model = trips.planner(repository(), destination = destination)
        planner(model, FixtureTiles(), dark = false)
        compose.runOnUiThread { model.swap() }
        compose.captureWindow(name)
    }

    @Test
    fun noTripLight() = noTrip("plan_trip_no_trip_light")

    @Test
    @Config(qualifiers = "+night")
    fun noTripDark() = noTrip("plan_trip_no_trip_dark")

    // The place picker, with what it finds

    private fun picker(name: String) {
        val geocoder = TripFixtures.Geocoder(
            found = mapOf(
                "Universidades, Cali" to listOf(
                    GeocodedPlace("Universidades, Cl. 13 #100-00, Cali, Valle del Cauca, Colombia", 3.3705, -76.5310),
                ),
            ),
        )
        val model = trips.picker(repository(), geocoder = geocoder)
        compose.setContent {
            CaliGoTheme {
                PlacePickerScreen(
                    model,
                    title = "¿A dónde vas?",
                    favorites = listOf(
                        FavoriteStop(id = "t12", stopId = "t12", name = "Universidades", customName = "Universidad", anchorLatitude = 3.45, anchorLongitude = -76.48),
                        FavoriteStop(id = "t9", stopId = "t9", name = "Centro", customName = "Trabajo", anchorLatitude = 3.45, anchorLongitude = -76.49),
                    ),
                    stations = listOf(
                        Station(10, "Estación Universidades", "Cl 13 - Kr 100", 3.3705, -76.5310),
                        Station(11, "Estación Chiminangos", "Cl 70 - Kr 1", 3.4801, -76.5001),
                    ),
                    onBack = {},
                    onPicked = {},
                    onPickOnMap = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("place_search").performTextReplacement("Universidades")
        compose.onNodeWithTag("search_address").performClick()
        compose.waitForIdle()
        compose.captureWindow(name)
    }

    @Test
    fun pickerLight() = picker("place_picker_light")

    @Test
    @Config(qualifiers = "+night")
    fun pickerDark() = picker("place_picker_dark")

    // The map point picker

    private fun mapPoint(name: String, dark: Boolean) {
        val tiles = FixtureTiles(dark = dark)
        compose.setContent {
            CaliGoTheme {
                MapPointPicker(
                    near = TripPlace(3.4516, -76.5320, "Mi ubicación", isDevice = true),
                    tiles = remember { tiles },
                    darkTiles = dark,
                    carto = true,
                    onBack = {},
                    onUse = {},
                    fadeIn = false,
                )
            }
        }
        compose.waitForIdle()
        waitForTiles(tiles, zoom = 16)
        compose.captureWindow(name)
    }

    @Test
    fun mapPointLight() = mapPoint("map_point_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun mapPointDark() = mapPoint("map_point_dark", dark = true)
}
