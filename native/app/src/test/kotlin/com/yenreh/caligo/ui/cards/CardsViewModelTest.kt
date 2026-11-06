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
 * The refreshOnOpen cases of cards_provider_test.dart, plus the fix that
 * ends a balance refresh on any error
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

    private fun card(id: String) = CardEntity(internalId = "i$id", id = id, name = "Card $id", balance = 100.0)

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
}
