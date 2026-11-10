package com.yenreh.caligo.ui.stops

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.MovingClock
import com.yenreh.caligo.ui.common.hourMinuteText
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The details sheet's own states, as the Flutter StopDetailsSheet has them */
@RunWith(AndroidJUnit4::class)
class StopDetailsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val stop = NearbyStop("500800", "Plaza de Cayzedo A1", 119.6)

    /** The moment the fixtures are built on, taken once */
    private val now = System.currentTimeMillis()

    private val bus = BusArrival(
        line = "A47",
        destination = "Valle Grande",
        arrivalTime = now + 600_000,
        vehicleId = "1",
    )

    private fun show(
        lines: Loaded<List<StopLine>>,
        refresh: suspend (NearbyStop) -> NearbyStop = { it },
        fetchOnOpen: Boolean = false,
        initial: NearbyStop = stop,
        onGoHere: ((TripPlace) -> Unit)? = null,
        save: suspend (String) -> FavoriteSave = { FavoriteSave.Saved },
        onDismiss: () -> Unit = {},
    ) {
        compose.setContentAt(now) {
            CaliGoTheme {
                StopDetailsContent(
                    initial = initial,
                    isSaved = false,
                    lines = lines,
                    actions = StopDetailsActions(refresh = refresh, loadLines = { emptyList() }, save = save),
                    onDismiss = onDismiss,
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
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("Valle Grande")) }
        compose.onNodeWithText("Updated: ${hourMinuteText(now)}").assertExists()
        compose.onNodeWithText("10 min").assertExists()
        assertFalse(exists(hasText("120 m")))
    }

    @Test
    fun aNearBusGoneFromARefreshIsAtTheStopThenPassedAsTheClockMoves() {
        val clock = MovingClock(now)
        val near = bus.copy(line = "E21", destination = "Universidades", arrivalTime = now + 90_000, vehicleId = "2")
        compose.setContent {
            AtTime(clock) {
                CaliGoTheme {
                    StopDetailsContent(
                        initial = stop.copy(arrivals = listOf(near, bus)),
                        isSaved = false,
                        lines = Loaded.Ready(emptyList()),
                        // The next answer no longer lists the near bus
                        actions = StopDetailsActions(
                            refresh = { it.copy(arrivals = listOf(bus)) },
                            loadLines = { emptyList() },
                            save = { FavoriteSave.Saved },
                        ),
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("2 min").assertExists()

        clock.advance(30_000)
        compose.onNodeWithContentDescription("Update arrivals").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("At the stop")) }
        compose.onNodeWithText("Universidades").assertExists()

        // A minute past its time
        clock.advance(120_000)
        compose.waitForIdle()
        compose.onNodeWithText("Passed 1 min ago").assertExists()
        assertFalse(exists(hasText("At the stop")))
        // Below the live row
        val passed = compose.onNodeWithTag("arrival_passed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val live = compose.onNodeWithText("Valle Grande").fetchSemanticsNode().boundsInRoot
        assertTrue(passed.top > live.top)

        // Four minutes past its time, gone
        clock.advance(180_000)
        compose.waitForIdle()
        assertFalse(exists(hasText("Universidades")))
        compose.onNodeWithText("Valle Grande").assertExists()
    }

    @Test
    fun aFirstAnswerThatFailsSaysSo() {
        show(Loaded.Ready(emptyList()), refresh = { throw NetworkApiException("Connection error") }, fetchOnOpen = true)

        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("Could not load arrivals")) }
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

    // Saving

    @Test
    fun aStopTheDiskFailsToSaveSaysSoAndStaysOpen() {
        var dismissed = false
        show(Loaded.Ready(emptyList()), save = { FavoriteSave.Failed }, onDismiss = { dismissed = true })

        compose.onNodeWithTag("save_stop").performScrollTo().performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasTestTag("stop_name_dialog")) }
        compose.onNodeWithText("Save").performClick()

        // The Flutter app said nothing
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("Could not save the stop")) }
        assertFalse(dismissed)
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
