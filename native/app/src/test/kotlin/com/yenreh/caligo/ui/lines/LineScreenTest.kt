package com.yenreh.caligo.ui.lines

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.OpenStop
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.util.Calendar
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Flutter LineScreen over fixture tiles and a fake repository: its
 * loading, failed and empty states, the directions, the status line with
 * the hours and the buses this way, the stops on the map and in the list,
 * and the buses asked for only while it is on show
 */
@RunWith(AndroidJUnit4::class)
class LineScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val route = listOf(
        LineStop("504107", "Aguablanca B3", 3.4141, -76.4812, 0, 1),
        LineStop("514484", "Tv 103 con Kr 28F", 3.4109, -76.4798, 0, 2),
        LineStop("500300", "Estación Andrés Sanín", 3.4250, -76.4950, 0, 3),
        LineStop("500301", "Estación Andrés Sanín B", 3.4252, -76.4951, 1, 1),
        LineStop("514485", "Kr 28F con Cl 103", 3.4110, -76.4797, 1, 2),
        LineStop("504108", "Aguablanca B4", 3.4142, -76.4811, 1, 3),
    )

    private val buses = listOf(
        LineBus("32051", 3.4120, -76.4805, direction = 0),
        LineBus("12081", 3.4200, -76.4900, direction = 1),
        LineBus("11002", 3.4180, -76.4880, direction = null),
    )

    /** 2026-09-29 at 21:30, local time */
    private val evening = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 21, 30)
    }.timeInMillis

    private val opened = mutableListOf<OpenStop>()

    /** Whether the screen is on show */
    private var shown by mutableStateOf(true)

    private fun repository(stops: List<LineStop> = route) = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf("A47" to stops)
        this.buses = mapOf("A47" to this@LineScreenTest.buses)
    }

    private fun show(repository: FakeStopsRepository = repository(), hours: LineHours? = null): LineViewModel {
        val model = LineViewModel("A47", repository)
        compose.setContent {
            CaliGoTheme {
                LineScreen(
                    model,
                    hours = hours,
                    tiles = remember { FixtureTiles() },
                    darkTiles = false,
                    carto = true,
                    onBack = {},
                    details = { open, _ -> opened.add(open) },
                    shown = shown,
                    now = { evening },
                    fadeIn = false,
                )
            }
        }
        compose.waitForIdle()
        return model
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    private val refresh = hasContentDescription("Update bus positions")

    // The states

    @Test
    fun aRouteLoadingShowsTheSpinner() {
        val repository = repository()
        val held = CompletableDeferred<Unit>()
        repository.routeAnswer = { held.await() }
        show(repository)

        compose.onNodeWithText("A47").assertExists()
        assertFalse(exists(hasTestTag("map")))
        assertFalse(exists(hasTestTag("line_message")))
        held.complete(Unit)
        waitFor(hasTestTag("map"))
    }

    @Test
    fun aRouteThatFailsOffersToRetry() {
        val repository = repository()
        repository.routeFailure = NetworkApiException("Connection error")
        show(repository)
        // The Flutter screen said the arrivals failed
        waitFor(hasText("Could not load the route"))

        repository.routeFailure = null
        compose.onNodeWithText("Retry").performClick()

        waitFor(hasTestTag("map"))
        assertEquals(2, repository.routesAsked.size)
    }

    @Test
    fun aLineWithoutARouteSaysSo() {
        show(repository(emptyList()))

        waitFor(hasText("No route for this line"))
        assertFalse(exists(hasText("Retry")))
    }

    // The route

    @Test
    fun theRouteShowsOneDirectionNamedByWhereEachEnds() {
        show()
        waitFor(hasTestTag("map"))

        compose.onNodeWithText("To Estación Andrés Sanín").assertIsSelected()
        compose.onNodeWithText("To Aguablanca B4").assertExists()
        compose.onNodeWithText("Aguablanca B3").assertExists()
        assertFalse(exists(hasText("Kr 28F con Cl 103")))
        assertEquals(3, compose.onAllNodes(hasTestTag("route_stop_row")).fetchSemanticsNodes().size)

        compose.onNodeWithText("To Aguablanca B4").performClick()

        waitFor(hasText("Kr 28F con Cl 103"))
        assertFalse(exists(hasText("Tv 103 con Kr 28F")))
        compose.onNodeWithText("To Aguablanca B4").assertIsSelected()
    }

    @Test
    fun aRouteOfOneDirectionHasNoToggle() {
        show(repository(route.take(3)))
        waitFor(hasTestTag("map"))

        assertFalse(exists(hasTestTag("direction_0")))
    }

    @Test
    fun theStatusSaysTheHoursAndTheBusesThisWay() {
        // Past midnight: running at 21:30
        show(hours = LineHours(4.hours + 23.minutes, 19.minutes))

        waitFor(hasText("Runs 04:23–00:19 · 1 bus this way"))
    }

    @Test
    fun aLineNotRunningSaysSoWithItsHours() {
        show(hours = LineHours(5.hours, 20.hours + 9.minutes))

        waitFor(hasText("Not running now · 05:00–20:09 · 1 bus this way"))
    }

    @Test
    fun noBusesThisWaySaysSo() {
        val repository = repository()
        repository.buses = mapOf("A47" to listOf(buses[1]))
        show(repository)

        waitFor(hasText("No buses this way"))
    }

    @Test
    fun theBusesGoingThisWayAndThoseNotPlacedAreOnTheMap() {
        show()
        waitFor(hasTestTag("bus_32051"))

        // Faded, but there: the service could not say which way it goes
        assertTrue(exists(hasTestTag("bus_11002")))
        // Going the other way would read as coming
        assertFalse(exists(hasTestTag("bus_12081")))
        assertTrue(exists(hasTestTag("route_stop_504107")))
        assertFalse(exists(hasTestTag("route_stop_514485")))
        assertTrue(exists(hasContentDescription("32051")))
    }

    // Picking a stop

    @Test
    fun tappingARowPicksTheStopAndOpensItsDetailsThere() {
        val model = show()
        waitFor(hasText("Tv 103 con Kr 28F"))

        compose.onNodeWithText("Tv 103 con Kr 28F").performClick()

        compose.waitUntil(5_000) { opened.isNotEmpty() }
        val open = opened.last()
        assertEquals("514484", open.stop.id)
        assertEquals(0.0, open.stop.distanceMeters, 0.0)
        assertEquals(3.4109, open.anchorLatitude, 0.0)
        assertEquals(-76.4798, open.stop.longitude!!, 0.0)
        assertEquals("514484", model.screen.value.selectedStopId)
        assertEquals(16.0, model.map!!.camera.zoom, 1e-6)
        compose.onNode(hasTestTag("route_stop_row") and hasText("Tv 103 con Kr 28F")).assertIsSelected()
    }

    @Test
    fun tappingAStopOnTheMapDoesTheSame() {
        val model = show()
        waitFor(hasTestTag("route_stop_500300"))

        compose.onNodeWithTag("route_stop_500300", useUnmergedTree = true).performTouchInput { click() }

        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals("500300", opened.last().stop.id)
        assertEquals("500300", model.screen.value.selectedStopId)
    }

    // The buses, while on show

    @Test
    fun theBusesAreAskedForWhileOnShowAndTheButtonAsksAgain() {
        val repository = repository()
        show(repository)
        waitFor(refresh)
        assertEquals(1, repository.busesAsked.size)

        val held = CompletableDeferred<Unit>()
        repository.busAnswer = { held.await() }
        compose.onNode(refresh).performClick()

        waitFor(hasTestTag("updating_buses"))
        assertEquals(2, repository.busesAsked.size)
        // The buses on show stay while the answer comes
        assertTrue(exists(hasTestTag("bus_32051")))
        held.complete(Unit)
        waitFor(refresh)
    }

    @Test
    fun offShowTheBusesAreLetGoAndAskedForAgainOnReturn() {
        val repository = repository()
        show(repository)
        waitFor(hasTestTag("bus_32051"))

        shown = false
        compose.waitUntil(5_000) { exists(hasTestTag("updating_buses")) }
        assertFalse(exists(hasTestTag("bus_32051")))
        assertEquals(1, repository.busesAsked.size)

        shown = true
        waitFor(hasTestTag("bus_32051"))
        assertEquals(2, repository.busesAsked.size)
    }

    @Test
    fun busesThatFailHideAndTheButtonStaysToTryAgain() {
        val repository = repository()
        repository.busFailure = NetworkApiException("Connection error")
        show(repository)
        waitFor(refresh)

        assertFalse(exists(hasTestTag("bus_11002")))
        assertFalse(exists(hasText("bus this way", substring = true)))
    }
}
