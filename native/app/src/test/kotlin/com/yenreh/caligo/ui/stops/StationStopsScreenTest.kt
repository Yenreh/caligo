package com.yenreh.caligo.ui.stops

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Flutter StationStopsScreen: a station's platforms, and saving it whole */
@RunWith(AndroidJUnit4::class)
class StationStopsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val station = Station(12, "Estación Universidades", "Cl 16 - Kr 100", 3.37, -76.53)
    private val done = mutableListOf<Unit>()

    private fun bus(line: String) =
        BusArrival(line = line, destination = "Menga", arrivalTime = System.currentTimeMillis() + 600_000, vehicleId = line)

    private val platforms = listOf(
        NearbyStop("520100", "Universidades A", 40.0, listOf(bus("T31"), bus("E37"), bus("T31"))),
        NearbyStop("520101", "Universidades B", 55.0),
    )

    private fun show(repository: FakeStopsRepository) {
        val viewModel = StopsViewModel(repository, settings = MutableStateFlow(Settings(isLoading = false)))
        compose.setContent {
            CaliGoTheme { StationStopsScreen(station, viewModel, onBack = {}, onDone = { done.add(Unit) }) }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    @Test
    fun listsThePlatformsAroundTheStation() {
        val repository = FakeStopsRepository(emptyList(), platforms)
        show(repository)

        waitFor(hasText("Universidades A"))
        compose.onNodeWithText("40 m · T31, E37").assertExists()
        compose.onNodeWithText("55 m").assertExists()
        assertEquals(listOf(GeoPoint(3.37, -76.53)), repository.askedAround)
    }

    @Test
    fun aFailureOrNothingAroundSaysSo() {
        val failing = FakeStopsRepository(emptyList(), platforms)
        failing.failure = NetworkApiException("Connection error")
        show(failing)

        waitFor(hasText("Could not load arrivals"))
    }

    @Test
    fun nothingAroundSaysSo() {
        show(FakeStopsRepository(emptyList(), emptyList()))

        waitFor(hasText("No stops within 300 m"))
    }

    @Test
    fun theStarSavesTheWholeAreaAndCloses() {
        val repository = FakeStopsRepository(emptyList(), platforms)
        show(repository)

        compose.onNodeWithTag("save_area").performClick()
        waitFor(hasText("Save this area"))
        compose.onNodeWithTag("custom_name").performTextReplacement("U")
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(5_000) { done.isNotEmpty() }
        val area = repository.added.single()
        assertEquals("station-12", area.id)
        assertEquals("Estación Universidades", area.name)
        assertEquals("U", area.customName)
        assertTrue(area.isArea)
        assertEquals(GeoPoint(3.37, -76.53), GeoPoint(area.anchorLatitude, area.anchorLongitude))
    }

    @Test
    fun anAreaAlreadySavedStaysOpen() {
        val repository = FakeStopsRepository(listOf(favorite("station-12", 3.37, -76.53)), platforms)
        show(repository)

        compose.onNodeWithTag("save_area").performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertTrue(done.isEmpty())
        assertTrue(repository.added.isEmpty())
        // The Flutter app said nothing
        waitFor(hasText("This area is already saved"))
    }

    @Test
    fun aStopWhoseIdARelinkedFavoriteHoldsSaysSo() {
        val relinked = favorite("520101", 3.37, -76.53).copy(stopId = "520999", customName = "Casa")
        val repository = FakeStopsRepository(listOf(relinked), platforms)
        show(repository)
        waitFor(hasText("Universidades B"))

        compose.onNodeWithText("Universidades B").performClick()
        waitFor(hasTestTag("stop_details"))
        compose.onNodeWithTag("save_stop").performScrollTo().performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.onNodeWithText("Save").performClick()

        waitFor(hasText("Casa was saved from this stop and now follows another. Remove it to save this one."))
        assertTrue(repository.added.isEmpty())
        // The sheet stays, to say so
        assertTrue(exists(hasTestTag("stop_details")))
    }

    @Test
    fun aPlatformOpensItsDetailsAnchoredAtTheStation() {
        val repository = FakeStopsRepository(emptyList(), platforms)
        show(repository)
        waitFor(hasText("Universidades B"))

        compose.onNodeWithText("Universidades B").performClick()
        waitFor(hasTestTag("stop_details"))
        compose.onNodeWithTag("save_stop").performClick()
        waitFor(hasTestTag("stop_name_dialog"))
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(5_000) { repository.added.isNotEmpty() }
        val added = repository.added.single()
        assertEquals("520101", added.stopId)
        assertEquals(GeoPoint(3.37, -76.53), GeoPoint(added.anchorLatitude, added.anchorLongitude))
        // The station screen stays: only the sheet closes
        assertTrue(done.isEmpty())
    }
}
