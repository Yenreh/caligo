package com.yenreh.caligo.ui.stops

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The details sheet's own states, as the Flutter StopDetailsSheet has them */
@RunWith(AndroidJUnit4::class)
class StopDetailsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val stop = NearbyStop("500800", "Plaza de Cayzedo A1", 119.6)

    private val bus = BusArrival(
        line = "A47",
        destination = "Valle Grande",
        arrivalTime = System.currentTimeMillis() + 600_000,
        vehicleId = "1",
    )

    private fun show(
        lines: Loaded<List<StopLine>>,
        refresh: suspend (NearbyStop) -> NearbyStop = { it },
        fetchOnOpen: Boolean = false,
        initial: NearbyStop = stop,
        onGoHere: ((TripPlace) -> Unit)? = null,
    ) {
        compose.setContent {
            CaliGoTheme {
                StopDetailsContent(
                    initial = initial,
                    isSaved = false,
                    lines = lines,
                    actions = StopDetailsActions(refresh = refresh, loadLines = { emptyList() }, save = { FavoriteSave.Saved }),
                    onDismiss = {},
                    fetchOnOpen = fetchOnOpen,
                    onGoHere = onGoHere,
                    anchorLatitude = 3.4500,
                    anchorLongitude = -76.5300,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aStopKnownWithoutItsBusesAsksAtOnceAndShowsNoDistance() {
        val answer = CompletableDeferred<NearbyStop>()
        show(Loaded.Ready(emptyList()), refresh = { answer.await() }, fetchOnOpen = true)

        // Waiting: neither the distance nor "no buses" yet
        assertFalse(exists(hasText("120 m")))
        assertFalse(exists(hasText("No buses coming")))

        answer.complete(stop.copy(arrivals = listOf(bus)))
        compose.waitUntil(5_000) { exists(hasText("Valle Grande")) }
        compose.onNodeWithText("Updated: ", substring = true).assertExists()
        assertFalse(exists(hasText("120 m")))
    }

    @Test
    fun aFirstAnswerThatFailsSaysSo() {
        show(Loaded.Ready(emptyList()), refresh = { throw NetworkApiException("Connection error") }, fetchOnOpen = true)

        compose.waitUntil(5_000) { exists(hasText("Could not load arrivals")) }
        compose.onNodeWithText("No buses coming").assertExists()
    }

    @Test
    fun theLinesServingTheStopShowByShortName() {
        show(Loaded.Ready(listOf(StopLine("P82", "TERMINAL CALIPSO"), StopLine("A47", "VALLE GRANDE"))))

        compose.onNodeWithText("120 m").assertExists()
        compose.onNodeWithText("P82").assertExists()
        compose.onNodeWithText("A47").assertExists()
    }

    @Test
    fun linesThatFailToLoadReadAsNoneListed() {
        show(Loaded.Failed(NetworkApiException("Connection error")))

        compose.onNodeWithText("No lines listed").assertExists()
    }

    // "Go here"

    @Test
    fun goHereTakesTheStopWhereItStandsOrWhereItWasFoundFrom() {
        val places = mutableListOf<TripPlace>()
        val placed = stop.copy(latitude = 3.4513, longitude = -76.5321)
        show(Loaded.Ready(emptyList()), initial = placed, onGoHere = { places.add(it) })

        compose.onNodeWithText("Go here").performScrollTo().performClick()

        assertEquals(listOf(TripPlace(3.4513, -76.5321, "Plaza de Cayzedo A1")), places)
    }

    @Test
    fun aStopNotPlacedGoesWhereItWasFoundFrom() {
        val places = mutableListOf<TripPlace>()
        show(Loaded.Ready(emptyList()), onGoHere = { places.add(it) })

        compose.onNodeWithTag("go_here").performScrollTo().performClick()

        assertEquals(listOf(TripPlace(3.4500, -76.5300, "Plaza de Cayzedo A1")), places)
    }

    @Test
    fun withNowhereToGoThereIsNoGoHere() {
        show(Loaded.Ready(emptyList()))

        assertFalse(exists(hasText("Go here")))
    }
}
