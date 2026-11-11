package com.yenreh.caligo.ui.trips

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.GeocodedPlace
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakePlacesRepository
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * place_picker_screen.dart over a picker fed by fakes: the device, the
 * map, favorites, stations and stops as typed, addresses looked up on
 * request, and what each hands back
 */
@RunWith(AndroidJUnit4::class)
class PlacePickerScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val trips = TripFixtures()
    private val picked = mutableListOf<TripPlace>()
    private var mapsOpened = 0

    @After
    fun clear() = trips.clear()

    private val stops = listOf(
        LineStop("1", "Universidades A1", 3.3700, -76.5300, 0, 1),
        LineStop("2", "Universidades B1", 3.3702, -76.5302, 0, 2),
        LineStop("3", "Cl 5 con Kr 39", 3.4200, -76.5400, 0, 3),
        LineStop("4", "Estación Calima", 3.4800, -76.5000, 0, 4),
    )

    private val stations = listOf(
        Station(10, "Estación Universidades", "Cl 13 - Kr 100", 3.3705, -76.5310),
        Station(11, "Estación Calima", "Cl 70 - Kr 1", 3.4801, -76.5001),
    )

    private val favorites = listOf(
        FavoriteStop(id = "3", stopId = "3", name = "Cl 5 con Kr 39", customName = "Casa", anchorLatitude = 3.4199, anchorLongitude = -76.5399),
        FavoriteStop(id = "u", name = "Universidades", anchorLatitude = 3.371, anchorLongitude = -76.531),
    )

    private fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply { routes = mapOf("A1" to stops) }

    private fun show(model: PlacePickerViewModel) {
        compose.setContent {
            CaliGoTheme {
                PlacePickerScreen(
                    model,
                    title = "Where to?",
                    favorites = favorites,
                    stations = stations,
                    onBack = {},
                    onPicked = { picked.add(it) },
                    onPickOnMap = { mapsOpened++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun type(text: String) = compose.onNodeWithTag("place_search").performTextReplacement(text)

    /** Bring the row reading [text] into view: the list makes its rows as they come in sight */
    private fun scrollTo(text: String) = compose.onNodeWithTag("places").performScrollToNode(hasText(text))

    private fun tile(text: String) = compose.onNode(hasTestTag("place_tile") and hasText(text))

    // What is listed

    @Test
    fun listsTheDeviceTheMapFavoritesAndStations() {
        show(trips.picker(repository()))

        compose.onNodeWithText("Where to?").assertExists()
        compose.onNodeWithText("Search a station, stop or address").assertExists()
        compose.onNodeWithText("My location").assertExists()
        compose.onNodeWithText("Pick on the map").assertExists()
        compose.onNodeWithText("FAVORITES").assertExists()
        compose.onNodeWithText("Casa").assertExists()
        scrollTo("STATIONS")
        scrollTo("Estación Calima")
        compose.onNodeWithText("Estación Calima").assertExists()
        // No stops before two letters, and no address before three
        assertFalse(exists(hasText("STOPS")))
        assertFalse(exists(hasTestTag("search_address")))
    }

    @Test
    fun typingFiltersWithoutAccentsAndListsStopsFromTwoLetters() {
        show(trips.picker(repository()))

        type("u")
        assertFalse(exists(hasText("STOPS")))

        type("univ")

        compose.onNodeWithText("Universidades").assertExists()
        assertFalse(exists(hasText("Casa")))
        scrollTo("Estación Universidades")
        compose.onNodeWithText("Estación Universidades").assertExists()
        assertFalse(exists(hasText("Estación Calima")))
        scrollTo("STOPS")
        scrollTo("Universidades A1")
        compose.onNodeWithText("Universidades A1").assertExists()
        scrollTo("Universidades B1")
        compose.onNodeWithText("Universidades B1").assertExists()

        // The station, then the stop of that name
        type("estacion")
        scrollTo("Cl 70 - Kr 1")
        scrollTo("STOPS")
        compose.onNode(hasTestTag("place_tile") and hasText("Estación Calima") and !hasText("Cl 70 - Kr 1")).assertExists()
    }

    // Addresses, on request

    @Test
    fun threeLettersOfferTheAddressSearchWhichListsWhatItFinds() {
        val geocoder = TripFixtures.Geocoder(
            found = mapOf("Unicentro, Cali" to listOf(GeocodedPlace("Unicentro, Cra. 100 #5-169, Cali, Valle del Cauca, Colombia", 3.3739, -76.5395))),
        )
        show(trips.picker(repository(), geocoder = geocoder))

        type("Unicentro")
        compose.onNodeWithText("Search “Unicentro” as an address or place").assertExists()
        assertTrue(geocoder.asked.isEmpty())

        compose.onNodeWithTag("search_address").performClick()

        assertEquals(listOf("Unicentro, Cali"), geocoder.asked)
        compose.onNodeWithText("ADDRESSES AND PLACES").assertExists()
        tile("Unicentro").performClick()
        assertEquals(listOf(TripPlace(3.3739, -76.5395, "Unicentro", detail = "Unicentro, Cra. 100 #5-169")), picked)

        // Typing on offers the search again
        type("Unicentr")
        compose.onNodeWithTag("search_address").assertExists()
    }

    @Test
    fun theKeyboardsSearchKeyLooksTheTextUpAndSaysWhenNothingIsFound() {
        val geocoder = TripFixtures.Geocoder()
        show(trips.picker(repository(), geocoder = geocoder))

        type("Unicentro")
        compose.onNodeWithTag("place_search").performImeAction()

        assertEquals(listOf("Unicentro, Cali"), geocoder.asked)
        compose.onNodeWithText(
            "Nothing found. Try a crossing, such as “Calle 5 con Carrera 38”, or pick the point on the map.",
        ).assertExists()
    }

    @Test
    fun aCrossingNamedByItsStopSaysSo() {
        // The geocoder knows neither; the stop named after the crossing answers
        show(trips.picker(repository()))

        type("Calle 5 # 39-10")
        compose.onNodeWithTag("search_address").performClick()

        compose.onNodeWithText("Cl 5 con Kr 39").assertExists()
        compose.onNodeWithText("MIO stop at that crossing, approximate").assertExists()
    }

    @Test
    fun aSearchOnItsWaySpinsAndTakesNoSecondTap() {
        val gate = CompletableDeferred<Unit>()
        val geocoder = TripFixtures.Geocoder().apply { answer = { gate.await() } }
        show(trips.picker(repository(), geocoder = geocoder))

        type("Unicentro")
        compose.onNodeWithTag("search_address").performClick()

        compose.onNodeWithTag("searching", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("search_address").assertIsNotEnabled()

        gate.complete(Unit)
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasTestTag("searching")) }
        assertEquals(1, geocoder.asked.size)
    }

    // What is handed back

    @Test
    fun aFavoriteIsItsStopWhereTheRoutesPlaceItAndItsAnchorOtherwise() {
        show(trips.picker(repository()))

        compose.onNodeWithText("Casa").performClick()
        compose.onNodeWithText("Universidades").performClick()

        assertEquals(
            listOf(
                TripPlace(3.4200, -76.5400, "Casa", kind = PlaceKind.STOP, detail = "Cl 5 con Kr 39", stopId = "3"),
                TripPlace(3.371, -76.531, "Universidades"),
            ),
            picked,
        )
    }

    @Test
    fun aStationOrAStopIsHandedBackWhereItIs() {
        show(trips.picker(repository()))
        type("calima")

        scrollTo("Cl 70 - Kr 1")
        tile("Cl 70 - Kr 1").performClick()
        scrollTo("STOPS")
        compose.onNode(hasTestTag("place_tile") and hasText("Estación Calima") and !hasText("Cl 70 - Kr 1")).performClick()

        assertEquals(
            listOf(
                TripPlace(3.4801, -76.5001, "Estación Calima", detail = "Cl 70 - Kr 1"),
                TripPlace(3.4800, -76.5000, "Estación Calima", kind = PlaceKind.STOP, stopId = "4"),
            ),
            picked,
        )
    }

    @Test
    fun myLocationHandsBackTheDevice() {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = GeoPoint(3.40, -76.50))
        show(trips.picker(repository(), location = location))

        compose.onNodeWithTag("use_device").performClick()

        assertEquals(listOf(TripPlace(3.40, -76.50, "My location", isDevice = true)), picked)
    }

    @Test
    fun myLocationSpinsWhileItWaitsAndSaysSoWithoutAFix() {
        val gate = CompletableDeferred<Unit>()
        val location = FakeLocationSource(permission = LocationPermission.GRANTED).apply { waiting = { gate.await() } }
        show(trips.picker(repository(), location = location))

        compose.onNodeWithTag("use_device").performClick()
        compose.onNodeWithTag("locating", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("use_device").assertIsNotEnabled()

        gate.complete(Unit)

        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("Could not get your location")) }
        assertFalse(exists(hasTestTag("locating")))
        assertTrue(picked.isEmpty())
    }

    @Test
    fun pickOnTheMapOpensTheMap() {
        show(trips.picker(repository()))

        compose.onNodeWithTag("pick_on_map").performClick()

        assertEquals(1, mapsOpened)
    }

    // The route

    @Test
    fun aPointFromTheMapIsNamedByAStopCloseByAndHandedBack() {
        val repository = repository()
        val model = trips.picker(repository)
        val stopsModel = StopsViewModel(
            repository,
            location = FakeLocationSource(),
            settings = MutableStateFlow(Settings(isLoading = false)),
        )
        val results = PickedPlaces(point = GeoPoint(3.4810, -76.5000))
        compose.setContent {
            CaliGoTheme {
                PlacePickerRoute(
                    origin = true,
                    stops = remember { stopsModel },
                    picked = results,
                    onBack = {},
                    onPicked = { picked.add(it) },
                    onPickOnMap = {},
                    viewModel = model,
                )
            }
        }
        compose.waitForIdle()

        assertEquals(listOf(TripPlace(3.4810, -76.5000, "Near Estación Calima")), picked)
        assertNull(results.point)
        compose.onNodeWithText("Where from?").assertExists()

        // Far from every stop, it is a point on the map
        compose.runOnUiThread { results.point = GeoPoint(3.4900, -76.5000) }
        compose.waitForIdle()
        assertEquals(TripPlace(3.4900, -76.5000, "Point on the map"), picked.last())
    }

    // Saved places

    private val saved = listOf(
        FavoritePlace(1, "Mamá", PlaceKind.ADDRESS, "Cl 9 # 40-12", null, 3.4310, -76.5450, null, 0),
        FavoritePlace(2, "U", PlaceKind.STOP, "Universidades A1", null, 3.3700, -76.5300, "1", 1),
    )

    private fun showSaved(): PlacePickerViewModel {
        trips.places = FakePlacesRepository(saved)
        return trips.picker(repository()).also(::show)
    }

    private fun savedTile(text: String) = compose.onNode(hasTestTag("saved_place") and hasText(text))

    private fun savedExists(text: String) =
        compose.onAllNodes(hasTestTag("saved_place") and hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun savedPlacesComeFirstAboveTheFavoritesAndAreHandedBackAsSaved() {
        showSaved()

        val label = compose.onNodeWithText("SAVED PLACES").fetchSemanticsNode().boundsInRoot.top
        val favoritesLabel = compose.onNodeWithText("FAVORITES").fetchSemanticsNode().boundsInRoot.top
        assertTrue(label < favoritesLabel)
        assertTrue(savedTile("Mamá").fetchSemanticsNode().boundsInRoot.top < favoritesLabel)
        // What they were called, below their own name
        savedTile("Cl 9 # 40-12").assertExists()

        savedTile("U").performClick()
        assertEquals(listOf(TripPlace(3.3700, -76.5300, "U", kind = PlaceKind.STOP, stopId = "1")), picked)
        compose.assertControlsAccessible()
    }

    @Test
    fun theSearchFiltersSavedPlacesByNameOrWhatTheyWereCalled() {
        showSaved()

        type("mama")
        savedTile("Mamá").assertExists()
        assertFalse(savedExists("U"))

        type("universidades")
        savedTile("U").assertExists()
        assertFalse(savedExists("Mamá"))

        type("trabajo")
        assertFalse(exists(hasText("SAVED PLACES")))
    }

    @Test
    fun aLongPressRenamesASavedPlace() {
        showSaved()

        savedTile("Mamá").performTouchInput { longClick() }
        compose.onNodeWithTag("place_sheet").assertExists()
        compose.onNodeWithText("Edit place").assertExists()
        compose.onNodeWithTag("place_name").performTextReplacement("  Casa de mamá ")
        compose.onNodeWithTag("place_save").performClick()

        compose.waitUntil(WAIT_TIMEOUT_MS) { savedExists("Casa de mamá") }
        assertFalse(savedExists("Mamá"))
        assertFalse(exists(hasTestTag("place_sheet")))
        assertTrue(picked.isEmpty())
    }

    @Test
    fun theCustomActionDeletesASavedPlace() {
        showSaved()

        val edit = savedTile("Mamá").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals("Edit place", edit.label)
        compose.runOnUiThread { edit.action() }
        // The long press says what it does too
        assertEquals("Edit place", savedTile("U").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)

        compose.onNodeWithTag("place_delete").performClick()

        compose.waitUntil(WAIT_TIMEOUT_MS) { !savedExists("Mamá") }
        savedTile("U").assertExists()
        assertFalse(exists(hasTestTag("place_sheet")))
    }

    @Test
    fun anEmptyNameCannotBeSaved() {
        showSaved()

        savedTile("U").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("place_name").performTextReplacement("   ")

        assertTrue(compose.onNodeWithTag("place_save").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
    }
}
