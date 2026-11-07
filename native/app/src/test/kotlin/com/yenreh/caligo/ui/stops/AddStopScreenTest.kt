package com.yenreh.caligo.ui.stops

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Flutter AddStopScreen's station and nearby tabs, and the stop
 * details they open, with a fake device location: the permission flow
 * as the nearby search meets it
 */
@RunWith(AndroidJUnit4::class)
class AddStopScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Station>()

    private val here = GeoPoint(3.4516, -76.5320)

    private val bus = BusArrival(
        line = "A47",
        destination = "Valle Grande",
        arrivalTime = System.currentTimeMillis() + 600_000,
        vehicleId = "1",
    )

    private val stations = listOf(
        Station(1, "Estación Alamos", "Av 3N - Cl 52N", 3.48, -76.51),
        Station(12, "Estación Universidades", "Cl 16 - Kr 100", 3.37, -76.53),
    )

    private fun repository(nearby: List<NearbyStop> = emptyList()) =
        FakeStopsRepository(emptyList(), nearby, stations = stations)

    private fun show(
        repository: FakeStopsRepository,
        location: FakeLocationSource = FakeLocationSource(),
        tab: Int = 0,
        explore: Boolean = false,
    ): StopsViewModel {
        val viewModel = StopsViewModel(
            repository,
            location = location,
            settings = MutableStateFlow(Settings(isLoading = false)),
        )
        compose.setContent {
            CaliGoTheme {
                AddStopScreen(viewModel, onBack = {}, onStation = { opened.add(it) }, explore = explore, initialTab = tab)
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { !exists(matcher) }

    // The title

    @Test
    fun addingOrLookingAroundOnlyChangesTheTitle() {
        show(repository(), explore = true)

        compose.onNodeWithText("Find stops").assertExists()
        compose.onNodeWithText("Stations").assertExists()
        compose.onNodeWithText("Near me").assertExists()
    }

    // The station catalog

    @Test
    fun theCatalogFiltersByNameOrAddressAndOpensAStation() {
        show(repository())
        waitFor(hasText("Estación Alamos"))

        compose.onNodeWithTag("station_search").performTextReplacement("kr 100")
        waitGone(hasText("Estación Alamos"))
        compose.onNodeWithText("Estación Universidades").performClick()

        assertEquals(listOf(12), opened.map { it.id })
    }

    @Test
    fun aCatalogThatFailsOffersToRetry() {
        val repository = repository()
        repository.catalogFailure = NetworkApiException("Connection error")
        show(repository)
        waitFor(hasText("Could not load arrivals"))

        repository.catalogFailure = null
        compose.onNodeWithText("Retry").performClick()

        waitFor(hasText("Estación Alamos"))
    }

    @Test
    fun theSearchStaysAsTheTabsChange() {
        show(repository(), FakeLocationSource(permission = LocationPermission.GRANTED, current = here))
        compose.onNodeWithTag("station_search").performTextReplacement("Alamos")

        compose.onNodeWithTag("tab_nearby").performClick()
        waitFor(hasText("No stops within 300 m"))
        compose.onNodeWithTag("tab_stations").performClick()

        waitFor(hasText("Alamos"))
        assertFalse(exists(hasText("Estación Universidades")))
    }

    // Near me: the permission flow

    @Test
    fun theFirstLookAsksForThePermissionAndListsTheStops() {
        val repository = repository(listOf(NearbyStop("500800", "Plaza de Cayzedo A1", 119.6, listOf(bus, bus))))
        val location = FakeLocationSource(answer = LocationPermission.GRANTED, current = here)
        show(repository, location, tab = NEARBY)

        waitFor(hasText("Plaza de Cayzedo A1"))
        assertEquals(1, location.requests)
        // The distance, and each line once
        compose.onNodeWithText("120 m · A47").assertExists()
        assertEquals(listOf(here), repository.askedAround)
    }

    @Test
    fun locationOffSaysToTurnItOnWithoutAPrompt() {
        val location = FakeLocationSource(enabled = false, current = here)
        show(repository(listOf(NearbyStop("a", "Stop a", 10.0))), location, tab = NEARBY)

        waitFor(hasText("Turn on location to find nearby stops."))
        assertEquals(0, location.requests)

        // Turned on, a retry finds the stops
        location.enabled = true
        compose.onNodeWithText("Retry").performClick()
        waitFor(hasText("Stop a"))
    }

    @Test
    fun aRefusedPermissionSaysLocationIsNeeded() {
        show(repository(), FakeLocationSource(answer = LocationPermission.DENIED), tab = NEARBY)

        waitFor(hasText("Turn on location to find nearby stops."))
    }

    @Test
    fun aPermissionRefusedForGoodSendsToTheSystemSettings() {
        show(repository(), FakeLocationSource(answer = LocationPermission.DENIED_FOREVER), tab = NEARBY)

        waitFor(hasText("Enable the location permission in system settings."))
    }

    @Test
    fun noFixInTimeSaysTheArrivalsFailed() {
        show(repository(), FakeLocationSource(permission = LocationPermission.GRANTED), tab = NEARBY)

        waitFor(hasText("Could not load arrivals"))
    }

    @Test
    fun noStopAroundOffersToLookAgain() {
        val repository = repository()
        show(repository, FakeLocationSource(permission = LocationPermission.GRANTED, current = here), tab = NEARBY)
        waitFor(hasText("No stops within 300 m"))

        compose.onNodeWithText("Retry").performClick()

        compose.waitUntil(5_000) { repository.nearbyCalls == 2 }
    }

    // The details of a stop found

    private fun openNearby(repository: FakeStopsRepository): StopsViewModel {
        val viewModel = show(repository, FakeLocationSource(permission = LocationPermission.GRANTED, current = here), NEARBY)
        waitFor(hasText("Plaza de Cayzedo A1"))
        compose.onNodeWithText("Plaza de Cayzedo A1").performClick()
        waitFor(hasTestTag("stop_details"))
        return viewModel
    }

    @Test
    fun aStopFoundIsSavedUnderTheLabelGivenAndTheSheetCloses() {
        val repository = repository(listOf(NearbyStop("500800", "Plaza de Cayzedo A1", 119.6, listOf(bus))))
        openNearby(repository)

        compose.onNodeWithTag("save_stop").performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.onNodeWithTag("custom_name").performTextReplacement("Trabajo")
        compose.onNodeWithText("Save").performClick()

        waitGone(hasTestTag("stop_details"))
        val added = repository.added.single()
        assertEquals("500800", added.id)
        assertEquals("500800", added.stopId)
        assertEquals("Trabajo", added.customName)
        // Anchored where it was found from
        assertEquals(here, GeoPoint(added.anchorLatitude, added.anchorLongitude))
    }

    @Test
    fun aDismissedNameSavesNothing() {
        val repository = repository(listOf(NearbyStop("500800", "Plaza de Cayzedo A1", 119.6)))
        openNearby(repository)

        compose.onNodeWithTag("save_stop").performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.onNodeWithText("Cancel").performClick()

        waitGone(hasTestTag("stop_name_dialog"))
        assertTrue(repository.added.isEmpty())
        compose.onNodeWithTag("stop_details").assertExists()
    }

    @Test
    fun aStopAlreadySavedCannotBeSavedAgain() {
        val repository = FakeStopsRepository(
            listOf(favorite("500800", 3.45, -76.53)),
            listOf(NearbyStop("500800", "Plaza de Cayzedo A1", 119.6)),
        )
        openNearby(repository)

        compose.onNodeWithText("That stop is already saved").assertExists()
        compose.onNodeWithTag("save_stop").assertIsNotEnabled()
    }

    @Test
    fun theDetailsRefreshOnDemandAndSayWhenThatFails() {
        val repository = repository(listOf(NearbyStop("500800", "Plaza de Cayzedo A1", 119.6, listOf(bus))))
        openNearby(repository)
        compose.onNodeWithText("No lines listed").assertExists()

        compose.onNode(hasContentDescription("Update arrivals")).performClick()
        waitFor(hasText("Updated: ", substring = true))

        repository.failure = NetworkApiException("Connection error")
        compose.onNode(hasContentDescription("Update arrivals")).performClick()
        waitFor(hasText("Could not load arrivals"))
        // What was shown stays
        compose.onNodeWithText("Valle Grande").assertExists()
    }

    private companion object {
        const val NEARBY = 1
    }
}
