package com.yenreh.caligo.ui.stops

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.common.ProviderRetry
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The map screen as the add-stop screen shows it, over fixture tiles, a
 * fake repository and a fake device location: searching, the markers and
 * the list, picking a stop, the credits, the compass and the location
 * button
 */
@RunWith(AndroidJUnit4::class)
class MapStopsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val cali = GeoPoint(3.4516, -76.5320)

    private val around = listOf(
        NearbyStop("500800", "Plaza de Cayzedo A1", 119.5),
        NearbyStop("500751", "La Ermita A2", 187.5),
        NearbyStop("500802", "Cl 12 con Kr 5", 262.0),
    )

    private val places = mapOf(
        "500800" to GeoPoint(3.4513, -76.5331),
        "500751" to GeoPoint(3.4531, -76.5310),
    )

    private val opened = mutableListOf<OpenStop>()
    private val cache = MapStopsViewModelTest.MemoryJsonCache()

    private fun repository(stops: List<NearbyStop> = around) =
        FakeStopsRepository(emptyList(), stops).apply { located = places }

    /** Whether the screen is on show; off, it is left */
    private var shown by mutableStateOf(true)

    private fun show(
        repository: FakeStopsRepository = repository(),
        location: FakeLocationSource = FakeLocationSource(),
        carto: Boolean = true,
    ): MapStopsViewModel {
        val model = MapStopsViewModel(repository, location, cache, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), ProviderRetry.none)
        compose.setContent {
            if (shown) CaliGoTheme {
                MapStopsScreen(
                    model,
                    remember { FixtureTiles() },
                    darkTiles = false,
                    carto = carto,
                    onOpen = { opened.add(it) },
                    fadeIn = false,
                )
            }
        }
        waitFor(hasTestTag("map"))
        return model
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(matcher) }

    private val searchHere = hasContentDescription("Search here")

    // Searching

    @Test
    fun beforeASearchItSaysHowToUseIt() {
        show()

        compose.onNodeWithText("Move the map and search to see stops anywhere").assertExists()
        assertFalse(exists(hasTestTag("map_stops")))
    }

    @Test
    fun searchingListsTheStopsAroundTheCrosshairAndMarksThosePlaced() {
        val repository = repository()
        show(repository)

        compose.onNode(searchHere).performClick()

        waitFor(hasText("Plaza de Cayzedo A1"))
        compose.onNodeWithText("Cl 12 con Kr 5").assertExists()
        assertEquals(listOf(cali), repository.askedAround)
        waitFor(hasTestTag("marker_500800"))
        assertTrue(exists(hasTestTag("marker_500751")))
        // Not placed: in the list, not on the map
        assertFalse(exists(hasTestTag("marker_500802")))
    }

    @Test
    fun noStopsAroundSaysSo() {
        show(repository(emptyList()))

        compose.onNode(searchHere).performClick()

        waitFor(hasText("No stops within 300 m"))
    }

    @Test
    fun aSearchThatFailsSaysSo() {
        val repository = repository()
        repository.failure = NetworkApiException("Connection error")
        show(repository)

        compose.onNode(searchHere).performClick()

        waitFor(hasText("Could not load arrivals"))
    }

    // Picking a stop

    @Test
    fun tappingAMarkerPicksItsStopAndOpensItsDetails() {
        val model = show()
        compose.onNode(searchHere).performClick()
        waitFor(hasTestTag("marker_500751"))

        compose.onNodeWithTag("marker_500751", useUnmergedTree = true).performTouchInput { click() }

        compose.waitUntil(WAIT_TIMEOUT_MS) { opened.isNotEmpty() }
        val open = opened.single()
        assertEquals("500751", open.stop.id)
        // Its details look around the point searched
        assertEquals(cali.latitude, open.anchorLatitude, 0.0)
        assertEquals(cali.longitude, open.anchorLongitude, 0.0)
        assertEquals("500751", model.screen.value.selectedStopId)
        // Brought close up
        assertEquals(17.0, model.screen.value.map!!.camera.zoom, 1e-6)
        assertEquals(3.4531, model.screen.value.map!!.camera.center.latitude, 1e-6)
    }

    @Test
    fun tappingARowDoesTheSame() {
        val model = show()
        compose.onNode(searchHere).performClick()
        waitFor(hasText("Plaza de Cayzedo A1"))

        compose.onNodeWithText("Plaza de Cayzedo A1").performClick()

        assertEquals("500800", opened.single().stop.id)
        assertEquals("500800", model.screen.value.selectedStopId)
        compose.onNode(hasTestTag("nearby_stop") and hasText("Plaza de Cayzedo A1")).assertIsSelected()
    }

    // The controls

    @Test
    fun theCreditsNameTheSourcesAndCartoOnlyForItsTiles() {
        show(carto = true)

        compose.onNodeWithContentDescription("Map credits").performClick()

        waitFor(hasTestTag("map_credits"))
        compose.onNodeWithText("© OpenStreetMap contributors").assertExists()
        compose.onNodeWithText("© CARTO").assertExists()
        compose.onNodeWithText("© Metro Cali S.A.").assertExists()

        compose.onNodeWithContentDescription("Map credits").performClick()
        waitGone(hasTestTag("map_credits"))
    }

    @Test
    fun withOpenStreetMapsTilesTheCreditsLeaveCartoOut() {
        show(carto = false)

        compose.onNodeWithContentDescription("Map credits").performClick()

        waitFor(hasTestTag("map_credits"))
        compose.onNodeWithText("© CARTO").assertDoesNotExist()
    }

    @Test
    fun movingTheMapPutsTheCreditsAway() {
        show()
        compose.onNodeWithContentDescription("Map credits").performClick()
        waitFor(hasTestTag("map_credits"))

        compose.onNodeWithTag("map").performTouchInput {
            swipe(start = center, end = center - Offset(200f, 0f), durationMillis = 500)
        }

        waitGone(hasTestTag("map_credits"))
    }

    @Test
    fun theCompassShowsOnceTurnedAndFacesNorthWhenTapped() {
        val model = show()
        assertFalse(exists(hasContentDescription("Face north")))

        compose.runOnUiThread { model.screen.value.map!!.rotate(40f) }
        waitFor(hasContentDescription("Face north"))

        compose.onNodeWithContentDescription("Face north").performClick()

        waitGone(hasContentDescription("Face north"))
        assertEquals(0f, model.screen.value.map!!.camera.rotation, 0.01f)
    }

    @Test
    fun theLocationButtonSpinsWhileLocatingAndThenMoves() {
        val fix = CompletableDeferred<Unit>()
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = GeoPoint(3.42, -76.54))
        location.waiting = { fix.await() }
        val model = show(location = location)
        // Still for the quiet fix on opening: only the button's own spins it
        compose.onNodeWithTag("locating").assertDoesNotExist()

        compose.onNodeWithContentDescription("My location").performClick()
        waitFor(hasTestTag("locating"))
        fix.complete(Unit)

        waitGone(hasTestTag("locating"))
        assertEquals(3.42, model.screen.value.map!!.camera.center.latitude, 1e-6)
        assertEquals(16.0, model.screen.value.map!!.camera.zoom, 1e-6)
    }

    @Test
    fun theLocationButtonAsksForThePermission() {
        val location = FakeLocationSource(current = GeoPoint(3.42, -76.54))
        val model = show(location = location)

        compose.onNodeWithContentDescription("My location").performClick()

        compose.waitUntil(WAIT_TIMEOUT_MS) { location.requests == 1 }
        compose.waitUntil(WAIT_TIMEOUT_MS) { model.screen.value.map!!.camera.center.latitude == 3.42 }
    }

    @Test
    fun leavingKeepsWhereTheMapWas() {
        val model = show()
        compose.runOnUiThread { model.screen.value.map!!.move(GeoPoint(3.44, -76.52), 14.0) }
        compose.waitForIdle()

        shown = false
        compose.waitForIdle()

        assertEquals("[3.44,-76.52,14.0]", cache.text("map_camera"))
    }

    @Test
    fun theAddScreenOpensOnTheMap() {
        val stops = StopsViewModel(
            repository(),
            location = FakeLocationSource(),
            settings = MutableStateFlow(Settings(isLoading = false)),
        )
        val model = MapStopsViewModel(repository(), FakeLocationSource(), cache, CoroutineScope(Dispatchers.Unconfined))
        val linesModel = LinesViewModel(FakeStopsRepository(emptyList(), emptyList()))
        compose.setContent {
            CaliGoTheme {
                AddStopScreen(stops, linesModel, onBack = {}, onStation = {}, map = { onOpen ->
                    MapStopsScreen(model, remember { FixtureTiles() }, darkTiles = false, carto = true, onOpen = onOpen)
                })
            }
        }

        waitFor(hasTestTag("map"))
        compose.onNodeWithText("Map").assertExists()
        compose.onNode(searchHere).performClick()
        waitFor(hasText("La Ermita A2"))

        // A row opens the stop's details over the add screen
        compose.onNodeWithText("La Ermita A2").performClick()
        waitFor(hasTestTag("stop_details"))
    }
}
