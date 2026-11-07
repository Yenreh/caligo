package com.yenreh.caligo.ui.cards

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.ui.FakeCardRepository
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Flutter CreateCardScreen and EditCardScreen: their fields, validators and saves */
@RunWith(AndroidJUnit4::class)
class CardFormRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeCardRepository(HomeSamples.cards)
    private val viewModel = CardsViewModel(repository)
    private var done = 0
    private var left = 0

    private fun create() {
        compose.setContent {
            CaliGoTheme { CreateCardRoute(viewModel, onBack = { left++ }, onDone = { done++ }) }
        }
        compose.waitForIdle()
    }

    private fun edit(cardId: String) {
        compose.setContent {
            CaliGoTheme { EditCardRoute(cardId, viewModel, onBack = { left++ }, onDone = { done++ }) }
        }
        compose.waitForIdle()
    }

    private fun field(tag: String) = compose.onNodeWithTag(tag)

    private fun type(tag: String, text: String) = field(tag).performTextReplacement(text)

    private fun submit() {
        field("card_submit").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** What the field holds, its hint apart */
    private fun text(tag: String) = field(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun hasError(message: String) = SemanticsMatcher.expectValue(SemanticsProperties.Error, message)

    private val ids get() = repository.cards.map { it.id }

    // Creating

    @Test
    fun aNewCardStartsWithTheUsualPrefixAndSuffix() {
        create()

        compose.onNodeWithText("Create New Card").assertExists()
        assertEquals("1906", text("card_prefix"))
        assertEquals("1", text("card_suffix"))
        assertEquals("", text("card_id"))
        assertEquals("", text("card_name"))
        compose.onNodeWithText("Enter card ID").assertExists()
        compose.onNodeWithText("Card name").assertExists()
        compose.onNodeWithText("Create Card").assertExists()
    }

    @Test
    fun submittingNothingNamesBothMissingFieldsAndSavesNothing() {
        create()

        submit()

        compose.onNodeWithText("ID is required").assertExists()
        compose.onNodeWithText("Name is required").assertExists()
        field("card_id").assert(hasError("ID is required"))
        field("card_name").assert(hasError("Name is required"))
        assertEquals(2, repository.cards.size)
        assertEquals(0, done)
    }

    @Test
    fun spacesAloneCountAsNothing() {
        create()
        type("card_id", "   ")
        type("card_name", "  ")

        submit()

        compose.onNodeWithText("ID is required").assertExists()
        compose.onNodeWithText("Name is required").assertExists()
    }

    @Test
    fun aMessageStaysUntilTheNextSubmit() {
        create()
        submit()

        type("card_id", "06051870")
        // As a Flutter Form without autovalidate: typing does not clear it
        compose.onNodeWithText("ID is required").assertExists()

        submit()
        compose.onNodeWithText("ID is required").assertDoesNotExist()
        compose.onNodeWithText("Name is required").assertExists()
        assertEquals(0, done)
    }

    @Test
    fun aValidCardIsSavedTrimmedAndTheScreenCloses() {
        create()
        type("card_id", " 06051870 ")
        type("card_name", " Bus ")
        type("card_prefix", " 19 ")
        type("card_suffix", "")

        submit()

        val saved = repository.cards.single { it.id == "06051870" }
        assertEquals("Bus", saved.name)
        assertEquals("19", saved.prefix)
        assertEquals("", saved.suffix)
        assertNull(saved.balance)
        assertEquals(1, done)
        assertEquals(0, left)
    }

    @Test
    fun aSaveThatFailsSaysSoAndStays() {
        create()
        repository.saveFailure = IllegalStateException("disk full")
        type("card_id", "06051870")
        type("card_name", "Bus")

        submit()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Error creating card")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, done)
        field("card_submit").assertIsEnabled()
        // Told here: not left to show elsewhere as a failed balance update
        assertNull(viewModel.cards.value.error)
    }

    @Test
    fun aNumberAlreadySavedIsRefusedOnCreateToo() {
        create()
        type("card_id", " 06051868 ")
        type("card_name", "Again")

        submit()

        compose.onNodeWithText("A card with this ID already exists").assertExists()
        assertEquals(1, ids.count { it == "06051868" })
        assertEquals(0, done)
    }

    @Test
    fun theButtonSpinsAndHoldsWhileSaving() {
        create()
        val gate = CompletableDeferred<Unit>()
        repository.saving = { gate.await() }
        type("card_id", "06051870")
        type("card_name", "Bus")

        submit()

        field("card_submit").assertIsNotEnabled()
        compose.onNodeWithText("Create Card").assertDoesNotExist()
        // A second tap saves nothing more
        submit()

        gate.complete(Unit)
        compose.waitUntil(5_000) { done == 1 }
        assertEquals(1, ids.count { it == "06051870" })
    }

    @Test
    fun theArrowLeavesWithoutSaving() {
        create()
        type("card_id", "06051870")

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, left)
        assertEquals(2, repository.cards.size)
    }

    // Editing

    @Test
    fun theFieldsStartFromTheCard() {
        edit("06051869")

        compose.onNodeWithText("Edit Card").assertExists()
        assertEquals("06051869", text("card_id"))
        assertEquals("Universidad", text("card_name"))
        assertEquals("19", text("card_prefix"))
        assertEquals("982", text("card_suffix"))
        compose.onNodeWithText("Save Changes").assertExists()
    }

    @Test
    fun aNumberAnotherCardHasIsRefused() {
        edit("06051869")
        type("card_id", "06051868")

        submit()

        compose.onNodeWithText("A card with this ID already exists").assertExists()
        assertTrue(repository.updated.isEmpty())
        assertEquals(0, done)
    }

    @Test
    fun theCardKeepsItsOwnNumberAndTheRestOfItself() {
        edit("06051869")
        type("card_name", " Trabajo ")
        type("card_suffix", "983")

        submit()

        val saved = repository.updated.single()
        assertEquals("2", saved.internalId)
        assertEquals("06051869", saved.id)
        assertEquals("Trabajo", saved.name)
        assertEquals("983", saved.suffix)
        // The balance and its date stay
        assertEquals(2400.0, saved.balance)
        assertEquals(HomeSamples.cards[1].lastUpdate, saved.lastUpdate)
        assertEquals(1, done)
    }

    @Test
    fun aNewNumberIsSavedAndTheFormStaysAsItCloses() {
        edit("06051869")
        type("card_id", "06051899")

        submit()

        assertEquals("06051899", repository.updated.single().id)
        assertEquals(1, done)
        // The route no longer finds the card by its old number: the form
        // stays rather than "No cards" while the screen goes
        compose.onNodeWithText("No cards").assertDoesNotExist()
        assertEquals("06051899", text("card_id"))
    }

    @Test
    fun anEmptyNameIsRefused() {
        edit("06051869")
        type("card_name", "")

        submit()

        compose.onNodeWithText("Name is required").assertExists()
        assertTrue(repository.updated.isEmpty())
    }

    @Test
    fun aSaveThatFailsSaysSo() {
        edit("06051869")
        repository.saveFailure = IllegalStateException("locked")

        submit()

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Error saving changes")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, done)
    }

    @Test
    fun aCardNotSavedShowsNoCards() {
        edit("nope")

        compose.onNodeWithText("Edit Card").assertExists()
        compose.onNodeWithText("No cards").assertExists()
        field("card_form").assertDoesNotExist()
    }

    @Test
    fun theCardsReadAgainNeverOverwriteWhatWasTyped() {
        edit("06051869")
        type("card_name", "Typed")

        viewModel.loadCards()
        compose.waitForIdle()

        assertEquals("Typed", text("card_name"))
    }

    @Test
    fun theFieldsFillOnceTheCardsHaveLoaded() {
        val loaded = CompletableDeferred<Unit>()
        val late = CardsViewModel(repository, ready = { loaded.await() })
        compose.setContent {
            CaliGoTheme { EditCardRoute("06051868", late, onBack = {}, onDone = {}) }
        }
        compose.waitForIdle()
        // Nothing to edit yet, as in Dart while the cards load
        compose.onNodeWithText("No cards").assertExists()

        loaded.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Mía")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("06051868", text("card_id"))
        field("card_name").performTextInput("!")
        assertEquals("Mía!", text("card_name"))
    }
}
