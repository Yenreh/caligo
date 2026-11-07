package com.yenreh.caligo.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stop screens as the app shows them, from the home, sharing the
 * activity's StopsViewModel: one put there first, over a fake repository
 * and location, so no test asks the real services
 */
@RunWith(AndroidJUnit4::class)
class StopsNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<CaliGoApp>().container

    private val stations = listOf(Station(12, "Estación Universidades", "Cl 16 - Kr 100", 3.37, -76.53))

    private fun show(repository: FakeStopsRepository, content: HomeContent = HomeContent.STOPS): StopsViewModel {
        container.settings.setHomeContent(content)
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = GeoPoint(3.4516, -76.532))
        val shared = ViewModelProvider.create(
            compose.activity,
            viewModelFactory {
                initializer { StopsViewModel(repository, location = location, settings = container.settings.settings) }
            },
        )[StopsViewModel::class]
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            CaliGoTheme { AppNavigation(settings, mapTiles = { remember { FixtureTiles() } }) }
        }
        compose.waitForIdle()
        return shared
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { !exists(matcher) }

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private val home = hasContentDescription("Settings")
    private val stopsTitle = hasText("Favorite stops")
    private val addFab = hasContentDescription("Add stop")

    @Test
    fun theBarsFavoritesButtonOpensTheStopsAndBackComesHome() {
        show(FakeStopsRepository(emptyList(), emptyList()), HomeContent.CARDS)

        compose.onNodeWithContentDescription("Favorites").performClick()
        waitFor(addFab)
        waitGone(home)

        systemBack()
        waitFor(home)
        waitGone(addFab)
    }

    @Test
    fun seeAllOnTheHomesStopsOpensTheStopsScreen() {
        show(FakeStopsRepository(listOf(favorite("a", 3.45, -76.53)), emptyList()))
        waitFor(hasText("Stop a"))

        compose.onNodeWithText("See all").performClick()

        waitFor(hasContentDescription("Edit stop"))
        compose.onNode(stopsTitle).assertExists()
    }

    @Test
    fun theBarsSearchOpensTheAddScreenToLookAround() {
        show(FakeStopsRepository(emptyList(), emptyList(), stations = stations))

        compose.onNodeWithContentDescription("Find stops").performClick()

        // On the map first, as the Flutter screen opens
        waitFor(hasTestTag("map"))
        compose.onNodeWithText("Find stops").assertExists()
        compose.onNodeWithTag("tab_stations").performClick()
        waitFor(hasText("Estación Universidades"))
    }

    @Test
    fun anAreaSavedFromAStationShowsOnTheStopsAndTheHome() {
        val repository = FakeStopsRepository(
            emptyList(),
            listOf(NearbyStop("520100", "Universidades A", 40.0)),
            stations = stations,
        )
        show(repository)

        // Home, stops, add, station
        compose.onNodeWithContentDescription("Favorites").performClick()
        waitFor(addFab)
        compose.onNode(addFab).performClick()
        waitFor(hasTestTag("tab_stations"))
        compose.onNodeWithTag("tab_stations").performClick()
        waitFor(hasText("Estación Universidades"))
        compose.onNodeWithText("Estación Universidades").performClick()
        waitFor(hasText("Universidades A"))

        compose.onNodeWithTag("save_area").performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.onNodeWithText("Save").performClick()

        // The station closes once saved: back on the add screen
        waitGone(hasText("Universidades A"))
        waitFor(hasTestTag("tab_stations"))
        assertEquals("station-12", repository.added.single().id)

        systemBack()
        waitFor(hasContentDescription("Edit stop"))
        compose.onNode(stopsTitle).assertExists()
        systemBack()
        waitFor(home)
        waitFor(hasText("Estación Universidades"))
    }

    @Test
    fun theStationsArrowComesBackToTheList() {
        show(FakeStopsRepository(emptyList(), emptyList(), stations = stations))
        compose.onNodeWithContentDescription("Find stops").performClick()
        waitFor(hasTestTag("tab_stations"))
        compose.onNodeWithTag("tab_stations").performClick()
        waitFor(hasText("Estación Universidades"))
        compose.onNodeWithText("Estación Universidades").performClick()
        waitFor(hasTestTag("save_area"))

        compose.onNodeWithContentDescription("Back").performClick()

        waitGone(hasTestTag("save_area"))
        waitFor(hasTestTag("tab_stations"))
    }
}
