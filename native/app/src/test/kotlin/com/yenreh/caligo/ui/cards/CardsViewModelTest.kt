@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.cards

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.ui.FakeCardRepository
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The refreshOnOpen cases of cards_provider_test.dart, the fix that ends
 * a balance refresh on any error, and the cards screens' work: create,
 * update, reorder and delete
 */
class CardsViewModelTest {
    private val store = ViewModelStore()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /**
     * runTest, then the view models cleared: a refresh left on its pace
     * would otherwise keep the test's virtual clock running for ever
     */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: FakeCardRepository, ready: suspend () -> Unit = {}): CardsViewModel =
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { CardsViewModel(repository, ready) } },
        )[CardsViewModel::class]

    private suspend fun ready(repository: FakeCardRepository) = viewModel(repository).also { it.loadCards().join() }

    private fun card(id: String, position: Int = 0) =
        CardEntity(internalId = "i$id", id = id, name = "Card $id", position = position, balance = 100.0)

    /** Three cards in an order already saved, as after any reorder */
    private fun ordered() = FakeCardRepository(listOf(card("a", 0), card("b", 1), card("c", 2)))

    private val CardsViewModel.state get() = cards.value

    // CardsNotifier.refreshOnOpen

    @Test
    fun asksForEveryBalanceWithoutAnnouncingIt() = test {
        val repository = FakeCardRepository(listOf(card("a"), card("b")))
        val cards = ready(repository)
        val announced = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cards.cards.collect { announced.add(it.lastRefreshSuccess) }
        }

        assertTrue(cards.refreshOnOpen())
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), repository.asked)
        assertEquals(listOf(9000.0, 9000.0), cards.state.cards.map { it.balance })
        assertTrue(announced.none { it })
        assertFalse(cards.state.isRefreshingAll)
    }

    @Test
    fun marksTheCardThatFailedAsOutOfDateQuietly() = test {
        val repository = FakeCardRepository(listOf(card("a"), card("b")), failing = setOf("a"))
        val cards = ready(repository)

        cards.refreshOnOpen()
        advanceUntilIdle()

        assertEquals("a", cards.state.refreshFailedCardId)
        // No error message: that is for refreshes the user asked for
        assertNull(cards.state.refreshError)
        assertEquals(9000.0, cards.state.cards.last().balance)
    }

    @Test
    fun asksOnlyOncePerLaunch() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)

        cards.refreshOnOpen()
        advanceUntilIdle()
        cards.refreshOnOpen()
        advanceUntilIdle()

        assertEquals(listOf("a"), repository.asked)
    }

    @Test
    fun waitsForTheCardsBeforeCountingAsDone() = test {
        val repository = FakeCardRepository(emptyList())

        assertFalse(viewModel(repository).refreshOnOpen())
    }

    // The first read waits for the app's startup work

    @Test
    fun readsTheCardsOnlyOnceTheStartupWorkIsDone() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val started = Job()
        val cards = viewModel(repository, ready = { started.join() })

        assertTrue(cards.state.isLoading)
        assertTrue(cards.state.cards.isEmpty())
        started.complete()

        assertFalse(cards.state.isLoading)
        assertEquals(listOf("a"), cards.state.cards.map { it.id })
    }

    // The fix: every error ends a refresh

    @Test
    fun anErrorOfAnyTypeEndsTheRefreshOfOneCard() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)

        repository.failure = IllegalStateException("not a balance")
        cards.refreshCardBalance("a").join()

        assertNull(cards.state.refreshingCardId)
        assertNotNull(cards.state.error)

        // And the next one goes ahead
        repository.failure = null
        cards.refreshCardBalance("a").join()
        assertEquals(9000.0, cards.state.cards.single().balance)
        assertTrue(cards.state.lastRefreshSuccess)
    }

    @Test
    fun anErrorOfAnyTypeEndsTheRefreshOfEveryCard() = test {
        val repository = FakeCardRepository(listOf(card("a"), card("b")))
        val cards = ready(repository)

        repository.failure = IllegalStateException("not a balance")
        cards.refreshAllBalances().join()

        assertFalse(cards.state.isRefreshingAll)
        assertEquals("a", cards.state.refreshFailedCardId)
        assertNotNull(cards.state.refreshError)
        assertFalse(cards.state.lastRefreshSuccess)
    }

    @Test
    fun aRefreshCutShortIsOverToo() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)
        repository.answer = { awaitCancellation() }

        val one = cards.refreshCardBalance("a")
        assertEquals("a", cards.state.refreshingCardId)
        one.cancel()
        one.join()
        assertNull(cards.state.refreshingCardId)

        val all = cards.refreshAllBalances()
        assertTrue(cards.state.isRefreshingAll)
        all.cancel()
        all.join()
        assertFalse(cards.state.isRefreshingAll)
    }

    @Test
    fun aRefreshTheUserAskedForReportsItsOutcome() = test {
        val repository = FakeCardRepository(listOf(card("a"), card("b")), failing = setOf("b"))
        val cards = ready(repository)

        cards.refreshCardBalance("a").join()
        assertTrue(cards.state.lastRefreshSuccess)

        cards.refreshCardBalance("b").join()
        assertFalse(cards.state.lastRefreshSuccess)
        assertTrue(cards.state.refreshError is ServerApiException)
        assertEquals("b", cards.state.refreshFailedCardId)
        // Kept until shown and cleared
        cards.clearRefreshError()
        assertNull(cards.state.refreshError)
        assertNull(cards.state.refreshFailedCardId)
    }

    @Test
    fun readsTheCardsAgainEachTimeTheyChangeElsewhere() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val reloads = MutableSharedFlow<Unit>()
        val cards = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { CardsViewModel(repository, reloads = reloads) } },
        )[CardsViewModel::class]
        advanceUntilIdle()
        assertEquals(listOf("a"), cards.state.cards.map { it.id })

        repository.cards.add(card("b"))
        reloads.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), cards.state.cards.map { it.id })
    }

    // Creating a card

    @Test
    fun createsACardUnderANewInternalIdAndReadsThemAgain() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)

        val saved = cards.createCard(id = "06051870", name = "Bus", prefix = "1906", suffix = "1").await()

        assertTrue(saved)
        val created = cards.state.cards.single { it.id == "06051870" }
        assertEquals("Bus", created.name)
        assertEquals("1906", created.prefix)
        assertEquals("1", created.suffix)
        assertEquals(0, created.position)
        // No balance until one is asked for
        assertNull(created.balance)
        assertEquals(4, UUID.fromString(created.internalId).version())
        assertFalse(cards.state.isLoading)
        // Nothing asked of the balance service
        assertTrue(repository.asked.isEmpty())
    }

    @Test
    fun eachNewCardGetsItsOwnInternalId() = test {
        val repository = FakeCardRepository(emptyList())
        val cards = ready(repository)

        cards.createCard(id = "1", name = "One").await()
        cards.createCard(id = "2", name = "Two").await()

        assertEquals(2, cards.state.cards.map { it.internalId }.toSet().size)
        // Prefix and suffix default to none
        assertTrue(cards.state.cards.all { it.prefix.isEmpty() && it.suffix.isEmpty() })
    }

    @Test
    fun aCreateThatFailsAnswersFalseAndKeepsTheError() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)
        repository.saveFailure = IllegalStateException("disk full")

        assertFalse(cards.createCard(id = "b", name = "B").await())

        assertNotNull(cards.state.error)
        assertEquals(listOf("a"), cards.state.cards.map { it.id })
    }

    @Test
    fun aNewCardTakesPositionZeroAndSortsByNameAmongThoseThere() = test {
        // As Dart does: not at the end of a list already reordered
        val repository = ordered()
        val cards = ready(repository)

        cards.createCard(id = "z", name = "Card 0").await()

        assertEquals(listOf("z", "a", "b", "c"), cards.state.cards.map { it.id })
    }

    @Test
    fun aNumberAlreadySavedIsSavedAgain() = test {
        // The view model leaves the check for a number taken to the screens
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)

        assertTrue(cards.createCard(id = "a", name = "Again").await())

        assertEquals(listOf("a", "a"), cards.state.cards.map { it.id })
    }

    // Editing a card

    @Test
    fun updatesACardByItsInternalIdAndReadsThemAgain() = test {
        val repository = FakeCardRepository(listOf(card("a", 0), card("b", 1)))
        val cards = ready(repository)
        val edited = cards.state.cards.first().copy(id = "a2", name = "Renamed")

        assertTrue(cards.updateCard(edited).await())

        assertEquals(listOf(edited), repository.updated)
        assertEquals(listOf("a2", "b"), cards.state.cards.map { it.id })
        assertEquals("Renamed", cards.state.cards.first().name)
        // Its balance stays
        assertEquals(100.0, cards.state.cards.first().balance)
    }

    @Test
    fun anUpdateThatFailsAnswersFalseAndKeepsTheError() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)
        repository.saveFailure = IllegalStateException("locked")

        assertFalse(cards.updateCard(cards.state.cards.single().copy(name = "New")).await())

        assertNotNull(cards.state.error)
        assertEquals("Card a", cards.state.cards.single().name)
    }

    // Reordering

    @Test
    fun movingDownCountsTheCardItselfAsFlutterDoes() = test {
        val repository = ordered()
        val cards = ready(repository)

        // From the first slot to before the third: between b and c
        cards.reorderCards(0, 2).join()

        assertEquals(listOf("b", "a", "c"), cards.state.cards.map { it.id })
        assertEquals(listOf(0, 1, 2), cards.state.cards.map { it.position })
        // Only the two whose position changed are saved
        assertEquals(listOf("b" to 0, "a" to 1), repository.updated.map { it.id to it.position })
    }

    @Test
    fun movingUpTakesTheNewIndexAsIs() = test {
        val repository = ordered()
        val cards = ready(repository)

        cards.reorderCards(2, 0).join()

        assertEquals(listOf("c", "a", "b"), cards.state.cards.map { it.id })
        assertEquals(listOf("c" to 0, "a" to 1, "b" to 2), repository.updated.map { it.id to it.position })
    }

    @Test
    fun movingToTheEndUsesTheCount() = test {
        val repository = ordered()
        val cards = ready(repository)

        cards.reorderCards(0, 3).join()

        assertEquals(listOf("b", "c", "a"), cards.state.cards.map { it.id })
        // Read again, the saved order is the same
        cards.loadCards().join()
        assertEquals(listOf("b", "c", "a"), cards.state.cards.map { it.id })
    }

    @Test
    fun cardsNeverReorderedAreNumberedAsTheyMove() = test {
        // Every card at position 0, as created
        val repository = FakeCardRepository(listOf(card("a"), card("b"), card("c")))
        val cards = ready(repository)

        cards.reorderCards(2, 0).join()

        assertEquals(listOf("c", "a", "b"), cards.state.cards.map { it.id })
        // c already had 0: only a and b are saved
        assertEquals(listOf("a" to 1, "b" to 2), repository.updated.map { it.id to it.position })
        cards.loadCards().join()
        assertEquals(listOf("c", "a", "b"), cards.state.cards.map { it.id })
    }

    @Test
    fun theNewOrderShowsBeforeItIsSaved() = test {
        val repository = ordered()
        val cards = ready(repository)
        val gate = CompletableDeferred<Unit>()
        repository.saving = { gate.await() }

        val saving = cards.reorderCards(1, 0)

        assertEquals(listOf("b", "a", "c"), cards.state.cards.map { it.id })
        assertTrue(repository.updated.isEmpty())
        gate.complete(Unit)
        saving.join()
        assertEquals(listOf("b", "a"), repository.updated.map { it.id })
    }

    @Test
    fun aReorderThatFailsToSaveKeepsTheErrorAndTheOrderShown() = test {
        val repository = ordered()
        val cards = ready(repository)
        repository.saveFailure = IllegalStateException("locked")

        cards.reorderCards(0, 2).join()

        assertNotNull(cards.state.error)
        assertEquals(listOf("b", "a", "c"), cards.state.cards.map { it.id })
    }

    @Test
    fun aMoveFromNowhereChangesNothing() = test {
        val repository = ordered()
        val cards = ready(repository)

        cards.reorderCards(5, 0).join()

        assertEquals(listOf("a", "b", "c"), cards.state.cards.map { it.id })
        assertTrue(repository.updated.isEmpty())
    }

    // Deleting

    @Test
    fun deletesByNumberAndReadsThemAgain() = test {
        val repository = FakeCardRepository(listOf(card("a"), card("b")))
        val cards = ready(repository)

        cards.deleteCardById("a").join()

        assertEquals(listOf("a"), repository.deleted)
        assertEquals(listOf("b"), cards.state.cards.map { it.id })
    }

    @Test
    fun aDeleteThatFailsKeepsTheErrorAndTheCard() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)
        repository.saveFailure = IllegalStateException("locked")

        cards.deleteCardById("a").join()

        assertNotNull(cards.state.error)
        assertEquals(listOf("a"), cards.state.cards.map { it.id })
    }

    @Test
    fun aSaveClearsTheLastRefreshSuccess() = test {
        val repository = FakeCardRepository(listOf(card("a")))
        val cards = ready(repository)
        cards.refreshCardBalance("a").join()
        assertTrue(cards.state.lastRefreshSuccess)

        cards.createCard(id = "b", name = "B").await()

        // Announced once, as Dart's copyWith clears it on any change
        assertFalse(cards.state.lastRefreshSuccess)
    }
}
