package com.yenreh.caligo.ui.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.FakeCardRepository
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.lab.HOLD_TO_DRAG_MILLIS
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Flutter CardsScreen's behavior, over the shared view model */
@RunWith(AndroidJUnit4::class)
class CardsRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val created = mutableListOf<Unit>()
    private val edited = mutableListOf<String>()
    private val left = mutableListOf<Unit>()

    private fun show(
        repository: FakeCardRepository,
        settings: Settings = HomeSamples.settings(HomeContent.CARDS).copy(refreshBalancesOnOpen = false),
        viewModel: CardsViewModel = CardsViewModel(repository),
    ): CardsViewModel {
        compose.setContent {
            CaliGoTheme {
                CardsRoute(
                    settings = settings,
                    viewModel = viewModel,
                    onBack = { left.add(Unit) },
                    onCreate = { created.add(Unit) },
                    onEdit = { edited.add(it) },
                )
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    /** The list item holding the card named [name], the one that moves */
    private fun item(name: String) =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText(name)))

    private fun SemanticsMatcher.Companion.customAction(label: String) =
        SemanticsMatcher("has the action $label") { node ->
            node.config.getOrElseNullable(SemanticsActions.CustomActions) { null }.orEmpty().any { it.label == label }
        }

    private fun performCustomAction(name: String, label: String) {
        // Scrolled to first, as a screen reader gets there: the list composes only what shows
        compose.onNodeWithTag("cards_list").performScrollToNode(hasText(name))
        val node = item(name).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
    }

    /** The button named [description] on the card at [index] */
    private fun cardButton(description: String, index: Int) =
        compose.onAllNodes(hasContentDescription(description) and hasAnyAncestor(hasTestTag("cards_list")))[index]

    private val names get() = compose.onAllNodes(hasText("Mía") or hasText("Universidad"))

    @Test
    fun listsEveryCardWithItsActions() {
        show(FakeCardRepository(HomeSamples.cards))

        compose.onNodeWithText("My cards").assertExists()
        compose.onNodeWithText("Mía").assertExists()
        compose.onNodeWithText("Universidad").assertExists()
        compose.onAllNodesWithContentDescription("Edit").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Delete").assertCountEquals(2)
        // One per card, and the bar's for all of them
        compose.onAllNodesWithContentDescription("Update balance").assertCountEquals(3)
        compose.onNodeWithContentDescription("New card").assertExists()
    }

    @Test
    fun theEmptyStateOffersTheFirstCard() {
        show(FakeCardRepository(emptyList()))

        compose.onNodeWithText("No cards").assertExists()
        // Nothing to refresh
        compose.onAllNodesWithContentDescription("Update balance").assertCountEquals(0)
        compose.onNodeWithText("Create first card").performClick()

        assertEquals(1, created.size)
    }

    @Test
    fun spinsWhileTheCardsLoad() {
        val loaded = CompletableDeferred<Unit>()
        val repository = FakeCardRepository(HomeSamples.cards)
        show(repository, viewModel = CardsViewModel(repository, ready = { loaded.await() }))

        assertTrue(exists(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)))
        compose.onNodeWithText("No cards").assertDoesNotExist()

        loaded.complete(Unit)
        waitFor(hasText("Mía"))
    }

    @Test
    fun theFloatingButtonOpensTheCreateScreen() {
        show(FakeCardRepository(HomeSamples.cards))

        compose.onNodeWithContentDescription("New card").performClick()

        assertEquals(1, created.size)
    }

    @Test
    fun editOpensTheCardByItsNumber() {
        show(FakeCardRepository(HomeSamples.cards))

        cardButton("Edit", 1).performClick()

        assertEquals(listOf("06051869"), edited)
    }

    @Test
    fun theArrowGoesBack() {
        show(FakeCardRepository(HomeSamples.cards))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, left.size)
    }

    @Test
    fun deleteAsksFirstAndCancelKeepsTheCard() {
        val repository = FakeCardRepository(HomeSamples.cards)
        show(repository)

        cardButton("Delete", 0).performClick()
        waitFor(hasText("Delete Card"))
        compose.onNodeWithText(
            "Are you sure you want to delete \"Mía\"? This action cannot be undone.",
        ).assertExists()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasText("Delete Card")) }

        assertTrue(repository.deleted.isEmpty())
        names.assertCountEquals(2)
    }

    @Test
    fun deleteConfirmedTakesTheCardAway() {
        val repository = FakeCardRepository(HomeSamples.cards)
        show(repository)

        cardButton("Delete", 0).performClick()
        waitFor(hasText("Delete Card"))
        // The sheet's button, not the cards' icon buttons
        compose.onNode(hasText("Delete")).performClick()

        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasText("Mía")) }
        assertEquals(listOf("06051868"), repository.deleted)
        compose.onNodeWithText("Universidad").assertExists()
        compose.onNodeWithText("Delete Card").assertDoesNotExist()
    }

    @Test
    fun aDeleteThatFailsSaysSoInItsOwnWords() {
        val repository = FakeCardRepository(HomeSamples.cards)
        show(repository)
        repository.saveFailure = IllegalStateException("locked")

        cardButton("Delete", 0).performClick()
        waitFor(hasText("Delete Card"))
        compose.onNode(hasText("Delete")).performClick()

        waitFor(hasText("Could not delete the card"))
        compose.onNodeWithText("Could not update balance").assertDoesNotExist()
        names.assertCountEquals(2)
    }

    @Test
    fun aMoveThatFailsToSaveSaysSoAndPutsTheCardBack() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val viewModel = show(repository)
        repository.saveFailure = IllegalStateException("locked")

        performCustomAction("Mía", "Move down")

        waitFor(hasText("Could not save the new order"))
        compose.onNodeWithText("Could not update balance").assertDoesNotExist()
        assertEquals(listOf("Mía", "Universidad"), viewModel.cards.value.cards.map { it.name })
    }

    @Test
    fun theLastCardGoneLeavesTheEmptyState() {
        val repository = FakeCardRepository(HomeSamples.cards.take(1))
        show(repository)

        compose.onNodeWithContentDescription("Delete").performClick()
        waitFor(hasText("Delete Card"))
        compose.onNode(hasText("Delete")).performClick()

        waitFor(hasText("Create first card"))
    }

    @Test
    fun refreshingEveryCardSpinsInTheBarAndSaysSoAtTheEnd() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val gate = CompletableDeferred<Unit>()
        repository.answer = { gate.await() }
        show(repository)

        compose.onNodeWithTag("refresh_all").performClick()
        compose.onNodeWithTag("refreshing_all").assertExists()

        gate.complete(Unit)
        waitFor(hasText("Balance updated"))
        compose.onNodeWithTag("refreshing_all").assertDoesNotExist()
        assertEquals(listOf("06051868", "06051869"), repository.asked)
    }

    @Test
    fun aRefreshThatFailsOffersARetry() {
        val repository = FakeCardRepository(HomeSamples.cards, failing = setOf("06051869"))
        show(repository)

        cardButton("Update balance", 1).performClick()

        waitFor(hasText("Balance service is unavailable. Try again later."))
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { repository.asked.size == 2 }
        assertEquals(listOf("06051869", "06051869"), repository.asked)
    }

    @Test
    fun aFailedRefreshKeepsTheStaleNoticeUntilTheNextRefresh() {
        val repository = FakeCardRepository(HomeSamples.cards, failing = setOf("06051869"))
        val viewModel = show(repository)

        cardButton("Update balance", 1).performClick()
        waitFor(hasText("Balance service is unavailable. Try again later."))
        // Past the beat after which the message is let go
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        compose.onNodeWithText("Last known balance").assertExists()
        assertEquals("06051869", viewModel.cards.value.refreshFailedCardId)

        // Another card's refresh says nothing of this one
        cardButton("Update balance", 0).performClick()
        waitFor(hasText("Balance updated"))
        compose.onNodeWithText("Last known balance").assertExists()

        // Its own next refresh, once it answers, lets it go
        repository.failing.clear()
        cardButton("Update balance", 1).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasText("Last known balance")) }
    }

    @Test
    fun aSuccessAnnouncedBeforeTheScreenOpenedIsNotAnnouncedAgain() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val viewModel = CardsViewModel(repository)
        runBlocking { viewModel.refreshCardBalance("06051868").join() }
        assertTrue(viewModel.cards.value.lastRefreshSuccess)

        show(repository, viewModel = viewModel)

        compose.onNodeWithText("Balance updated").assertDoesNotExist()
    }

    @Test
    fun theScreenReaderMovesACardDown() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val viewModel = show(repository)

        assertTrue(exists(SemanticsMatcher.customAction("Move down") and hasAnyDescendant(hasText("Mía"))))
        // The first one can only go down, the last one only up
        assertTrue(!exists(SemanticsMatcher.customAction("Move up") and hasAnyDescendant(hasText("Mía"))))
        assertTrue(!exists(SemanticsMatcher.customAction("Move to the end") and hasAnyDescendant(hasText("Universidad"))))

        performCustomAction("Mía", "Move down")

        assertEquals(listOf("Universidad", "Mía"), viewModel.cards.value.cards.map { it.name })
        assertEquals(listOf("06051869", "06051868"), repository.updated.map { it.id })
    }

    @Test
    fun theScreenReaderMovesACardToTheStart() {
        val cards = HomeSamples.cards + CardEntity(internalId = "3", id = "06051870", name = "Casa", position = 2)
        val repository = FakeCardRepository(cards)
        val viewModel = show(repository)

        performCustomAction("Casa", "Move to the start")

        assertEquals(listOf("Casa", "Mía", "Universidad"), viewModel.cards.value.cards.map { it.name })
    }

    @Test
    fun holdingACardAndDraggingItDownMovesIt() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val viewModel = show(repository)
        val height = item("Mía").fetchSemanticsNode().size.height.toFloat()

        item("Mía").performTouchInput {
            down(center)
            // Past the long press, then down over the next card
            advanceEventTime(HOLD_TO_DRAG_MILLIS + 100)
            repeat(10) { moveBy(Offset(0f, height / 10f)) }
            moveBy(Offset(0f, height / 4f))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf("Universidad", "Mía"), viewModel.cards.value.cards.map { it.name })
        assertEquals(listOf(0, 1), viewModel.cards.value.cards.map { it.position })
    }

    @Test
    fun aHoldShorterThanFluttersLongPressMovesNothing() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val viewModel = show(repository)
        val height = item("Mía").fetchSemanticsNode().size.height.toFloat()

        item("Mía").performTouchInput {
            down(center)
            // Past Android's long press, short of Flutter's
            advanceEventTime(450)
            repeat(10) { moveBy(Offset(0f, height / 10f)) }
            moveBy(Offset(0f, height / 4f))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf("Mía", "Universidad"), viewModel.cards.value.cards.map { it.name })
        assertTrue(repository.updated.isEmpty())
    }

    @Test
    fun aDragLetGoWhereItStartedChangesNothing() {
        val repository = FakeCardRepository(HomeSamples.cards)
        val viewModel = show(repository)

        item("Mía").performTouchInput {
            down(center)
            advanceEventTime(HOLD_TO_DRAG_MILLIS + 100)
            moveBy(Offset(0f, 20f))
            moveBy(Offset(0f, -20f))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf("Mía", "Universidad"), viewModel.cards.value.cards.map { it.name })
        assertTrue(repository.updated.isEmpty())
    }

    @Test
    fun aQuickSwipeScrollsTheListAndMovesNothing() {
        val many = (0 until 8).map {
            CardEntity(internalId = "$it", id = "0605186$it", name = "Card $it", position = it)
        }
        val repository = FakeCardRepository(many)
        val viewModel = show(repository)

        compose.onNodeWithTag("cards_list").performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.onNodeWithText("Card 0").assertDoesNotExist()
        assertEquals(many.map { it.name }, viewModel.cards.value.cards.map { it.name })
        assertTrue(repository.updated.isEmpty())
    }

    // The refresh on opening, which the Flutter home decides under this screen

    @Test
    fun theFirstCardOfALaunchThatFoundNoneIsRefreshed() {
        val repository = FakeCardRepository(emptyList())
        val viewModel = show(repository, settings = HomeSamples.settings(HomeContent.CARDS))
        assertTrue(repository.asked.isEmpty())

        runBlocking { viewModel.createCard(id = "06051868", name = "Mía").await() }

        compose.waitUntil(WAIT_TIMEOUT_MS) { repository.asked.isNotEmpty() }
        assertEquals(listOf("06051868"), repository.asked)
        // Quietly: no message for a refresh nobody asked for
        compose.waitForIdle()
        compose.onNodeWithText("Balance updated").assertDoesNotExist()
    }

    @Test
    fun notWhenTheSettingsSaySo() {
        val repository = FakeCardRepository(emptyList())
        val viewModel = show(repository)

        runBlocking { viewModel.createCard(id = "06051868", name = "Mía").await() }
        compose.waitForIdle()

        assertTrue(repository.asked.isEmpty())
    }
}
