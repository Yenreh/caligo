package com.yenreh.caligo.ui.stops

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.nearbyStop
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Flutter StopsScreen's behavior, over the shared view model */
@RunWith(AndroidJUnit4::class)
class StopsRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val added = mutableListOf<Unit>()
    private val left = mutableListOf<Unit>()

    private val bus = BusArrival(
        line = "A47",
        destination = "Valle Grande",
        arrivalTime = System.currentTimeMillis() + 600_000,
        vehicleId = "1",
    )

    private fun repository(vararg favorites: FavoriteStop, nearby: List<NearbyStop> = emptyList()) =
        FakeStopsRepository(favorites.toList(), nearby)

    private fun show(repository: FakeStopsRepository, sortedByProximity: Boolean = false): StopsViewModel {
        val settings = Settings(isLoading = false, sortStopsByProximity = sortedByProximity)
        val viewModel = StopsViewModel(repository, settings = MutableStateFlow(settings))
        compose.setContent {
            CaliGoTheme {
                StopsRoute(settings, viewModel, onBack = { left.add(Unit) }, onAdd = { added.add(Unit) })
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The line toggle of [line] in the edit sheet */
    private fun toggle(line: String) = hasTestTag("line_toggle") and hasAnyDescendant(hasText(line))

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { !exists(matcher) }

    /** The button named [description] on the stop titled [title] */
    private fun button(title: String, description: String) = compose.onNode(
        hasContentDescription(description) and
            hasAnyAncestor(hasTestTag("favorite_stop") and hasAnyDescendant(hasText(title))),
    )

    /** The list item holding the stop titled [title], the one that moves */
    private fun item(title: String) =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText(title)))

    @Test
    fun listsEveryStopWithItsButtonsAndMarksThoseOffTheHome() {
        show(repository(favorite("a", 3.45, -76.53), favorite("b", 3.47, -76.53).copy(showOnHome = false)))

        button("Stop a", "Hide from home").assertExists()
        button("Stop b", "Show on home").assertExists()
        button("Stop a", "Edit stop").assertExists()
        button("Stop a", "Remove stop").assertExists()
        compose.onNodeWithText("NOT ON HOME").assertExists()
    }

    @Test
    fun nothingSavedSaysSo() {
        show(repository())

        compose.onNodeWithTag("stops_empty").assertExists()
        compose.onNodeWithText("No saved stops").assertExists()
    }

    @Test
    fun theAddButtonAndTheArrowLeadAway() {
        show(repository())

        compose.onNodeWithContentDescription("Add stop").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, added.size)
        assertEquals(1, left.size)
    }

    @Test
    fun removingAStopTakesItOff() {
        val repository = repository(favorite("a", 3.45, -76.53), favorite("b", 3.47, -76.53))
        show(repository)

        button("Stop a", "Remove stop").performClick()

        waitGone(hasText("Stop a"))
        assertEquals(listOf("a"), repository.removed)
        compose.onNodeWithText("Stop b").assertExists()
    }

    @Test
    fun theHomeToggleKeepsAStopOffTheHomeAndBack() {
        val repository = repository(favorite("a", 3.45, -76.53))
        show(repository)
        repository.saved.clear()

        button("Stop a", "Hide from home").performClick()
        waitFor(hasText("NOT ON HOME"))
        button("Stop a", "Show on home").performClick()
        waitGone(hasText("NOT ON HOME"))

        assertEquals(listOf(false, true), repository.saved.map { it.showOnHome })
    }

    @Test
    fun editingSavesTheLabelAndTheLinesChosen() {
        val repository = FakeStopsRepository(
            listOf(favorite("a", 3.45, -76.53)),
            nearby = listOf(nearbyStop("a", arrivals = listOf(bus))),
            lines = mapOf("a" to listOf(StopLine("T31", "UNIVERSIDADES"))),
        )
        show(repository)
        repository.saved.clear()

        button("Stop a", "Edit stop").performClick()
        waitFor(hasTestTag("edit_favorite"))
        // The stop's own lines and those seen coming, in order
        waitFor(toggle("T31"))
        val offered = compose.onAllNodes(hasTestTag("line_toggle"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, offered.size)
        compose.onNode(toggle("A47"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("custom_name").performTextReplacement(" Casa ")
        compose.onNode(toggle("T31"), useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save").performClick()

        waitFor(hasText("Casa"))
        val saved = repository.saved.single()
        assertEquals("Casa", saved.customName)
        assertEquals(listOf("T31"), saved.lines)
        // The real name stays under the label
        compose.onNodeWithText("Stop a").assertExists()
    }

    @Test
    fun aChosenLineCanBeTakenOffEvenWhenNoLongerListed() {
        val repository = repository(favorite("a", 3.45, -76.53).copy(lines = listOf("E21")))
        show(repository)
        repository.saved.clear()

        button("Stop a", "Edit stop").performClick()
        waitFor(toggle("E21"))
        val chosen = compose.onNode(toggle("E21"), useUnmergedTree = true)
        assertTrue(chosen.fetchSemanticsNode().config[SemanticsProperties.Selected])
        chosen.performClick()
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(5_000) { repository.saved.isNotEmpty() }
        assertTrue(repository.saved.single().lines.isEmpty())
    }

    @Test
    fun cancellingAnEditChangesNothing() {
        val repository = repository(favorite("a", 3.45, -76.53))
        show(repository)
        repository.saved.clear()

        button("Stop a", "Edit stop").performClick()
        waitFor(hasTestTag("edit_favorite"))
        compose.onNodeWithTag("custom_name").performTextReplacement("Casa")
        compose.onNodeWithText("Cancel").performClick()

        waitGone(hasTestTag("edit_favorite"))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun aStopNoLongerReportedOffersAnotherToLinkTo() {
        val gone = favorite("a", 3.45, -76.53).copy(missingCount = FavoriteStop.MISSING_THRESHOLD)
        val repository = repository(
            gone,
            favorite("b", 3.47, -76.53),
            nearby = listOf(NearbyStop(id = "z", name = "Stop z", distanceMeters = 42.4)),
        )
        show(repository)
        repository.saved.clear()

        compose.onNodeWithText("This stop is no longer reported").assertExists()
        // Only the missing one offers it
        assertEquals(1, compose.onAllNodes(hasText("Link to another stop")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Link to another stop").performClick()
        waitFor(hasTestTag("relink_choices"))
        compose.onNodeWithText("42 m").assertExists()
        compose.onNodeWithText("Stop z").performClick()

        compose.waitUntil(5_000) { repository.saved.any { it.stopId == "z" } }
        val relinked = repository.saved.first { it.stopId == "z" }
        assertEquals("a", relinked.id)
        assertEquals(0, relinked.missingCount)
    }

    @Test
    fun theRelinkSheetSaysWhenNothingIsAround() {
        val gone = favorite("a", 3.45, -76.53).copy(missingCount = FavoriteStop.MISSING_THRESHOLD)
        show(repository(gone))

        compose.onNodeWithText("Link to another stop").performClick()

        waitFor(hasText("No stops within 300 m"))
    }

    @Test
    fun aFailedRefreshSaysSoAndRetries() {
        val repository = repository(favorite("a", 3.45, -76.53), nearby = listOf(nearbyStop("a")))
        show(repository)
        repository.failure = NetworkApiException("Connection error")

        compose.onNodeWithTag("refresh_stops").performClick()
        waitFor(hasText("Could not load arrivals"))

        repository.failure = null
        val calls = repository.nearbyCalls
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { repository.nearbyCalls > calls }
    }

    @Test
    fun anErrorFoundOnOpeningIsNotTold() {
        val repository = repository(favorite("a", 3.45, -76.53), nearby = listOf(nearbyStop("a")))
        repository.failure = NetworkApiException("Connection error")

        show(repository)

        assertFalse(exists(hasText("Could not load arrivals")))
    }

    /** Holds the first stop and drags it below the second */
    private fun dragFirstDown() {
        val height = item("Stop a").fetchSemanticsNode().size.height.toFloat()
        item("Stop a").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, height / 10f)) }
            moveBy(Offset(0f, height / 4f))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun inTheSavedOrderAStopIsHeldAndDragged() {
        val repository = repository(
            favorite("a", 3.45, -76.53).copy(position = 0),
            favorite("b", 3.47, -76.53).copy(position = 1),
        )
        val viewModel = show(repository)

        dragFirstDown()

        assertEquals(listOf("b", "a"), viewModel.stops.value.favorites.map { it.id })
    }

    @Test
    fun closestFirstTheDistanceDecidesAndNothingIsHeld() {
        val repository = repository(
            favorite("a", 3.45, -76.53).copy(position = 0),
            favorite("b", 3.47, -76.53).copy(position = 1),
        )
        val viewModel = show(repository, sortedByProximity = true)

        dragFirstDown()

        assertEquals(listOf("a", "b"), viewModel.stops.value.favorites.map { it.id })
    }
}
