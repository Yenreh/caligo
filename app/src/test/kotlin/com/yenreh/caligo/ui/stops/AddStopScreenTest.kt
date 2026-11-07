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
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TransitLine
import com.yenreh.caligo.ui.common.ProviderRetry
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.hourMinuteText
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.util.Calendar
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Flutter AddStopScreen's station, line and nearby tabs, and the stop
 * details they open, with a fake device location: the permission flow
 * as the nearby search meets it
 */
@RunWith(AndroidJUnit4::class)
class AddStopScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Station>()
    private val linesOpened = mutableListOf<TransitLine>()

    /** 2026-09-29 at 21:30, local time */
    private val evening = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 21, 30)
    }.timeInMillis

    private val here = GeoPoint(3.4516, -76.5320)

    /** The moment the arrivals are built on, taken once */
    private val now = System.currentTimeMillis()

    private val bus = BusArrival(
        line = "A47",
        destination = "Valle Grande",
        arrivalTime = now + 600_000,
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
        tab: Int = AddStopTabs.STATIONS,
        explore: Boolean = false,
    ): StopsViewModel {
        val viewModel = StopsViewModel(
            repository,
            location = location,
            settings = MutableStateFlow(Settings(isLoading = false)),
            // The failures themselves; RetryTest has their retries
            retry = ProviderRetry.none,
        )
        val lines = LinesViewModel(repository, retry = ProviderRetry.none)
        compose.setContentAt(now) {
            CaliGoTheme {
                AddStopScreen(
                    viewModel,
                    lines,
                    onBack = {},
                    onStation = { opened.add(it) },
                    onLine = { linesOpened.add(it) },
                    explore = explore,
                    initialTab = tab,
                    now = { evening },
                )
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(matcher) }

    // The title

    @Test
    fun addingOrLookingAroundOnlyChangesTheTitle() {
        show(repository(), explore = true)

        compose.onNodeWithText("Find stops").assertExists()
        compose.onNodeWithText("Stations").assertExists()
        compose.onNodeWithText("Lines").assertExists()
        compose.onNodeWithText("Near me").assertExists()
    }

    // The lines

    private fun withLines() = repository().apply {
        transitLines = listOf(TransitLine(347, "A47"), TransitLine(131, "T31"), TransitLine(0, "A52"))
        hours = mapOf(
            // Runs past midnight: still running at 21:30
            "T31" to LineHours(4.hours + 30.minutes, 19.minutes),
            "A47" to LineHours(5.hours, 20.hours + 9.minutes),
        )
    }

    @Test
    fun theLinesShowTheirHoursAndWhetherTheyRunNow() {
        show(withLines(), tab = LINES)
        waitFor(hasText("04:30 – 00:19"))

        compose.onNodeWithText("05:00 – 20:09").assertExists()
        compose.onNodeWithText("Running now").assertExists()
        compose.onNodeWithText("Not running now").assertExists()
        // Without hours, the name once, on its chip, and no status: the
        // Flutter list put it in the title too
        assertEquals(1, compose.onAllNodes(hasText("A52"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("Hours unknown"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theLineSearchIgnoresCaseAndOpensALine() {
        val repository = withLines()
        show(repository, tab = LINES)
        waitFor(hasText("A47"))

        compose.onNodeWithTag("line_search").performTextReplacement(" t3 ")
        waitGone(hasText("A47"))
        compose.onNodeWithText("T31").performClick()

        assertEquals(listOf("T31"), linesOpened.map { it.name })
        // Loaded once, kept as the tabs change
        compose.onNodeWithTag("tab_stations").performClick()
        compose.onNodeWithTag("tab_lines").performClick()
        waitFor(hasText("T31"))
        assertEquals(1, repository.lineCalls)
        assertEquals(1, repository.hourCalls)
    }

    @Test
    fun linesThatFailOfferToRetryAndHoursThatFailOnlyHideTheStatus() {
        val repository = withLines()
        repository.catalogFailure = NetworkApiException("Connection error")
        repository.hourFailure = NetworkApiException("Connection error")
        show(repository, tab = LINES)
        // Not "Could not load arrivals", as the Flutter app put it
        waitFor(hasText("Could not load the lines"))

        repository.catalogFailure = null
        compose.onNodeWithText("Retry").performClick()

        waitFor(hasText("T31"))
        assertFalse(exists(hasText("Running now")))
        assertFalse(exists(hasText("04:30 – 00:19")))
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
        // Not "Could not load arrivals", as the Flutter app put it
        waitFor(hasText("Could not load the stations"))

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
    fun noFixInTimeSaysSo() {
        show(repository(), FakeLocationSource(permission = LocationPermission.GRANTED), tab = NEARBY)

        // Not "Could not load arrivals", as the Flutter app put it
        waitFor(hasText("Could not get your location in time"))
    }

    @Test
    fun noStopAroundOffersToLookAgain() {
        val repository = repository()
        show(repository, FakeLocationSource(permission = LocationPermission.GRANTED, current = here), tab = NEARBY)
        waitFor(hasText("No stops within 300 m"))

        compose.onNodeWithText("Retry").performClick()

        compose.waitUntil(WAIT_TIMEOUT_MS) { repository.nearbyCalls == 2 }
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

        compose.onNodeWithText("10 min").assertExists()

        compose.onNode(hasContentDescription("Update arrivals")).performClick()
        waitFor(hasText("Updated: ${hourMinuteText(now)}", substring = true))

        repository.failure = NetworkApiException("Connection error")
        compose.onNode(hasContentDescription("Update arrivals")).performClick()
        waitFor(hasText("Could not load arrivals"))
        // What was shown stays
        compose.onNodeWithText("Valle Grande").assertExists()
    }

    private companion object {
        const val NEARBY = AddStopTabs.NEARBY
        const val LINES = AddStopTabs.LINES
    }
}
