package com.yenreh.caligo.ui.trips

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.R
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.captureScreen
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.settlePress
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.FakePlacesRepository
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The saved places at Pixel size, in Spanish and English, light and
 * dark, and at font scale 2: the planner's chips, its stars empty and
 * filled, the sheets that name and edit a place, and the picker's
 * section.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class SavedPlacesScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Named in the language of the test */
    private val trips = ApplicationProvider.getApplicationContext<Context>().run {
        TripFixtures(
            deviceName = getString(R.string.my_location),
            names = PlaceNames(getString(R.string.my_location), getString(R.string.point_on_map)) { getString(R.string.near_stop, it) },
        )
    }

    @After
    fun clear() = trips.clear()

    private val trunk = listOf(
        "Chiminangos", "Flora Industrial", "Salomia", "Popular", "Manzanares", "Fátima", "Piloto",
        "San Nicolás", "Ermita", "Centro", "San Bosco", "Estadio", "Universidades",
    )

    private fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf("T31" to trips.line("t", 0..12, names = trunk))
    }

    private fun at(x: Double, y: Double) = trips.point(x, y)

    private val saved = listOf(
        FavoritePlace(1, "Casa", PlaceKind.ADDRESS, "Cl 5 # 38-20", null, at(0.0, 0.5).latitude, at(0.0, 0.5).longitude, null, 0),
        FavoritePlace(2, "Trabajo", PlaceKind.STOP, "Centro", null, at(9.0, 0.0).latitude, at(9.0, 0.0).longitude, "t9", 1),
        FavoritePlace(3, "U", PlaceKind.PLACE, "Universidad del Valle", "Cl. 13 #100-00", at(12.5, 0.5).latitude, at(12.5, 0.5).longitude, null, 2),
        FavoritePlace(4, "Casa de mi mamá", PlaceKind.ADDRESS, "Kr 39 # 9-50", null, at(4.0, 1.0).latitude, at(4.0, 1.0).longitude, null, 3),
        FavoritePlace(5, "Gimnasio", PlaceKind.ADDRESS, "Cl 9 # 40-12", null, at(6.0, 1.0).latitude, at(6.0, 1.0).longitude, null, 4),
    )

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private fun planner(model: PlanTripViewModel) {
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
                    now = { trips.clock },
                    fadeIn = false,
                )
            }
        }
        compose.waitForIdle()
    }

    // The planner with nowhere to go yet: the saved places as chips

    private fun chips(name: String) {
        trips.places = FakePlacesRepository(saved)
        planner(trips.planner(repository(), destination = null))
        waitFor(hasTestTag("quick_plans"))
        compose.captureWindow(name)
        compose.assertControlsAccessible()
    }

    @Test
    fun chipsLight() = chips("saved_places_chips_light")

    @Test
    @Config(qualifiers = "+night")
    fun chipsDark() = chips("saved_places_chips_dark")

    @Test
    @Config(qualifiers = "+en")
    fun chipsEnglish() = chips("saved_places_chips_en")

    @Test
    @Config(fontScale = 2f)
    fun chipsFontScale2() = chips("saved_places_chips_font2")

    // A chip deleted: the snackbar that brings it back

    private fun deleted(name: String) {
        trips.places = FakePlacesRepository(saved)
        val model = trips.planner(repository(), destination = null)
        planner(model)
        waitFor(hasTestTag("quick_plans"))
        model.deletePlace(saved[3].id)
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasText(saved[3].name, substring = true)).fetchSemanticsNodes().size == 1 }
        compose.captureWindow(name)
    }

    @Test
    fun deletedLight() = deleted("saved_places_deleted_light")

    @Test
    @Config(qualifiers = "+night")
    fun deletedDark() = deleted("saved_places_deleted_dark")

    @Test
    @Config(qualifiers = "+en")
    fun deletedEnglish() = deleted("saved_places_deleted_en")

    @Test
    @Config(fontScale = 2f)
    fun deletedFontScale2() = deleted("saved_places_deleted_font2")

    // The stars: the origin saved, filled; the destination not, empty

    private fun stars(name: String) {
        trips.places = FakePlacesRepository(saved)
        val model = trips.planner(repository(), destination = trips.place(12.0, 0.5, "Estación Universidades"))
        compose.runOnUiThread { model.choose(origin = true, place = saved[0].tripPlace) }
        planner(model)
        waitFor(hasTestTag("option_0"))
        compose.captureWindow(name)
        compose.assertControlsAccessible()
    }

    @Test
    fun starsLight() = stars("saved_places_stars_light")

    @Test
    @Config(qualifiers = "+night")
    fun starsDark() = stars("saved_places_stars_dark")

    @Test
    @Config(qualifiers = "+en")
    fun starsEnglish() = stars("saved_places_stars_en")

    @Test
    @Config(fontScale = 2f)
    fun starsFontScale2() = stars("saved_places_stars_font2")

    // Naming a place to save it, from the empty star

    private fun save(name: String) {
        planner(trips.planner(repository(), destination = trips.place(12.0, 0.5, "Universidad del Valle, Cl. 13 #100-00")))
        compose.onNodeWithTag("destination_star").performClick()
        waitFor(hasTestTag("place_sheet"))
        compose.captureScreen(name)
    }

    @Test
    fun saveLight() = save("saved_places_save_light")

    @Test
    @Config(qualifiers = "+night")
    fun saveDark() = save("saved_places_save_dark")

    @Test
    @Config(qualifiers = "+en")
    fun saveEnglish() = save("saved_places_save_en")

    @Test
    @Config(fontScale = 2f)
    fun saveFontScale2() = save("saved_places_save_font2")

    // Editing a place, from a long press on its chip

    private fun edit(name: String) {
        trips.places = FakePlacesRepository(saved)
        planner(trips.planner(repository(), destination = null))
        waitFor(hasTestTag("quick_plans"))
        compose.onNode(hasTestTag("quick_plan") and hasText("U")).performTouchInput { longClick() }
        waitFor(hasTestTag("place_sheet"))
        compose.settlePress()
        compose.captureScreen(name)
    }

    @Test
    fun editLight() = edit("saved_places_edit_light")

    @Test
    @Config(qualifiers = "+night")
    fun editDark() = edit("saved_places_edit_dark")

    @Test
    @Config(qualifiers = "+en")
    fun editEnglish() = edit("saved_places_edit_en")

    @Test
    @Config(fontScale = 2f)
    fun editFontScale2() = edit("saved_places_edit_font2")

    // The picker, the saved places first

    private fun picker(name: String, title: String) {
        trips.places = FakePlacesRepository(saved)
        val model = trips.picker(repository())
        compose.setContent {
            CaliGoTheme {
                PlacePickerScreen(
                    model,
                    title = title,
                    favorites = listOf(
                        FavoriteStop(id = "t12", stopId = "t12", name = "Universidades", customName = "Universidad", anchorLatitude = 3.45, anchorLongitude = -76.48),
                    ),
                    stations = listOf(Station(10, "Estación Universidades", "Cl 13 - Kr 100", 3.3705, -76.5310)),
                    onBack = {},
                    onPicked = {},
                    onPickOnMap = {},
                )
            }
        }
        waitFor(hasTestTag("saved_place"))
        compose.onNodeWithTag("places").assertExists()
        compose.captureWindow(name)
        compose.assertControlsAccessible()
    }

    @Test
    fun pickerLight() = picker("saved_places_picker_light", "¿A dónde vas?")

    @Test
    @Config(qualifiers = "+night")
    fun pickerDark() = picker("saved_places_picker_dark", "¿A dónde vas?")

    @Test
    @Config(qualifiers = "+en")
    fun pickerEnglish() = picker("saved_places_picker_en", "Where to?")
}
