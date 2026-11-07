package com.yenreh.caligo.ui.stops

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.captureScreen
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The stop screens at Pixel size (1080 x 2400 px, 420 dpi), in Spanish,
 * light and dark: the favorites, the add screen with stops around and
 * with location off, a station's stops, a stop's details, the label
 * dialog and the edit sheet of a favorite
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class StopsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    /** A bus [minutes] away, with a margin so the countdown holds while the test runs */
    private fun bus(line: String, destination: String, minutes: Int) = BusArrival(
        line = line,
        destination = destination,
        arrivalTime = now + minutes * 60_000L + 20_000L,
        vehicleId = "$line-$minutes",
    )

    private val around = listOf(
        NearbyStop(
            "500800",
            "Plaza de Cayzedo A1",
            119.5,
            listOf(bus("E21", "Terminal Paso del Comercio", 2), bus("A47", "Valle Grande", 4), bus("T31", "Universidades", 9)),
        ),
        NearbyStop("500751", "La Ermita A2", 187.5, listOf(bus("P27D", "Av. Roosevelt-Capri", 6))),
        NearbyStop("500802", "Cl 12 con Kr 5", 262.0),
    )

    private val lines = mapOf(
        "500800" to listOf(
            StopLine("E21", "TERMINAL PASO DEL COMERCIO"),
            StopLine("A47", "VALLE GRANDE"),
            StopLine("T31", "UNIVERSIDADES"),
            StopLine("P27D", "AV. ROOSEVELT"),
        ),
    )

    private val station = Station(3, "Estación Plaza de Cayzedo", "Cl 12 - Kr 5", 3.4516, -76.532)

    private fun viewModel(location: FakeLocationSource = FakeLocationSource()) = StopsViewModel(
        FakeStopsRepository(emptyList(), around, lines = lines),
        location = location,
        settings = MutableStateFlow(Settings(isLoading = false)),
    )

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    // The favorites

    private val favorites = HomeSamples.stopsState.copy(
        favorites = HomeSamples.stops.map { if (it.isArea) it.copy(showOnHome = false) else it },
    )

    private fun stops(name: String) {
        compose.setContent { CaliGoTheme { StopsScreen(favorites, sortedByProximity = false) } }
        compose.captureWindow(name)
    }

    @Test
    fun stopsLight() = stops("stops_light")

    @Test
    @Config(qualifiers = "+night")
    fun stopsDark() = stops("stops_dark")

    // Adding: the stops around, and location off

    private fun nearby(name: String, location: FakeLocationSource) {
        val viewModel = viewModel(location)
        val linesModel = LinesViewModel(FakeStopsRepository(emptyList(), emptyList()))
        compose.setContent {
            CaliGoTheme { AddStopScreen(viewModel, linesModel, onBack = {}, onStation = {}, initialTab = AddStopTabs.NEARBY) }
        }
        waitFor(hasTestTag("nearby_stops") or hasTestTag("add_stop_message"))
        compose.captureWindow(name)
    }

    private fun located() = FakeLocationSource(permission = LocationPermission.GRANTED, current = GeoPoint(3.4516, -76.532))

    @Test
    fun addStopLight() = nearby("add_stop_nearby_light", located())

    @Test
    @Config(qualifiers = "+night")
    fun addStopDark() = nearby("add_stop_nearby_dark", located())

    @Test
    fun addStopNoLocationLight() = nearby("add_stop_no_location_light", FakeLocationSource(enabled = false))

    @Test
    @Config(qualifiers = "+night")
    fun addStopNoLocationDark() = nearby("add_stop_no_location_dark", FakeLocationSource(enabled = false))

    // A station, and the details of one of its stops

    private fun showStation(viewModel: StopsViewModel) {
        compose.setContent { CaliGoTheme { StationStopsScreen(station, viewModel, onBack = {}, onDone = {}) } }
        waitFor(hasTestTag("station_stops"))
    }

    private fun station(name: String) {
        showStation(viewModel())
        compose.captureWindow(name)
    }

    @Test
    fun stationLight() = station("station_stops_light")

    @Test
    @Config(qualifiers = "+night")
    fun stationDark() = station("station_stops_dark")

    private fun details(name: String) {
        showStation(viewModel())
        compose.onNodeWithText("Plaza de Cayzedo A1").performClick()
        waitFor(hasTestTag("stop_details"))
        waitFor(hasText("P27D"))
        compose.captureScreen(name)
    }

    @Test
    fun detailsLight() = details("stop_details_light")

    @Test
    @Config(qualifiers = "+night")
    fun detailsDark() = details("stop_details_dark")

    private fun nameDialog(name: String) {
        showStation(viewModel())
        compose.onNode(hasTestTag("save_area")).performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.captureScreen(name)
    }

    @Test
    fun nameDialogLight() = nameDialog("stop_name_dialog_light")

    @Test
    @Config(qualifiers = "+night")
    fun nameDialogDark() = nameDialog("stop_name_dialog_dark")

    // Editing a favorite

    private fun edit(name: String) {
        compose.setContent {
            CaliGoTheme {
                StopsScreen(
                    favorites,
                    sortedByProximity = false,
                    actions = StopsActions(loadLines = { listOf(StopLine("E21", ""), StopLine("T31", ""), StopLine("P47C", "")) }),
                )
            }
        }
        compose.onNode(
            hasContentDescription("Editar parada") and
                hasAnyAncestor(hasTestTag("favorite_stop") and hasAnyDescendant(hasText("Trabajo"))),
        ).performClick()
        waitFor(hasTestTag("edit_favorite"))
        waitFor(hasText("P47C"))
        compose.captureScreen(name)
    }

    @Test
    fun editLight() = edit("edit_favorite_light")

    @Test
    @Config(qualifiers = "+night")
    fun editDark() = edit("edit_favorite_dark")
}
