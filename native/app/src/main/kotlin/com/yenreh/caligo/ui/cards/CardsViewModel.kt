package com.yenreh.caligo.ui.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.repositories.CardRepository
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a failure in [CardsState.error] interrupted, when the user reads it apart */
enum class CardAction { REORDER, DELETE }

/** The saved cards and how their balance refreshes are going */
data class CardsState(
    val cards: List<CardEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** What [error] interrupted; null for a read or a balance refresh */
    val failedAction: CardAction? = null,
    val refreshingCardId: String? = null,
    val isRefreshingAll: Boolean = false,
    val refreshError: ApiException? = null,
    val refreshFailedCardId: String? = null,
    /**
     * True only in the state a successful refresh the user asked for
     * leaves; any later change clears it, as the Dart copyWith does
     */
    val lastRefreshSuccess: Boolean = false,
)

/**
 * The cards and their balances, as the Flutter app's CardsNotifier keeps
 * them: listing, creating, editing, reordering, deleting and refreshing.
 * Backups go through the settings' own view model.
 *
 * One per activity, shared by the home and the cards screens. Each action
 * returns the [Job] doing it, as the Dart methods return their Future;
 * those that report success return a [Deferred]. The work runs in the
 * view model's scope, so it ends even if the screen that asked is gone.
 */
class CardsViewModel(
    private val repository: CardRepository,
    /** Waited for before the first read: the app's startup work */
    private val ready: suspend () -> Unit = {},
    /** Each time the saved cards change elsewhere, as an import does */
    reloads: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    // Loading from the start: the first read follows at once
    private val state = MutableStateFlow(CardsState(isLoading = true))
    val cards: StateFlow<CardsState> = state.asStateFlow()

    /**
     * The refresh on opening runs once per launch, never again on coming
     * back from the background
     */
    private var openRefreshDone = false

    /**
     * Whether the home still has to decide on the refresh on opening.
     * Kept here, not in the home, so it outlives the home's composition
     * while another screen covers it, as the Flutter MainScreen's state
     * outlives the screens pushed over it.
     */
    var openRefreshPending = true

    init {
        viewModelScope.launch {
            ready()
            load()
            reloads.collect { load() }
        }
    }

    /** Every change but a successful refresh clears [CardsState.lastRefreshSuccess] */
    private fun set(change: (CardsState) -> CardsState) =
        state.update { change(it.copy(lastRefreshSuccess = false)) }

    fun loadCards(): Job = viewModelScope.launch { load() }

    private suspend fun load() {
        set { it.copy(isLoading = true, error = null, failedAction = null) }
        try {
            val cards = repository.getAllCards()
            set { it.copy(cards = cards, isLoading = false) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            set { it.copy(error = e.toString(), failedAction = null, isLoading = false) }
        }
    }

    /**
     * Save a new card at the end of the list, then read them all again.
     * True once saved; false, with [CardsState.error] set, if the save
     * failed.
     */
    fun createCard(
        id: String,
        name: String,
        prefix: String = "",
        suffix: String = "",
    ): Deferred<Boolean> = viewModelScope.async {
        saving {
            // After every card saved, whether reordered or not: the Flutter
            // app gave position 0, which sorts by name among the first
            val last = repository.getAllCards().maxOfOrNull { it.position } ?: -1
            val card = CardEntity(
                internalId = newInternalId(),
                id = id,
                name = name,
                prefix = prefix,
                suffix = suffix,
                position = last + 1,
            )
            repository.createCard(card)
        }
    }

    /** Save [card] over the one with its internal id, then read them all again */
    fun updateCard(card: CardEntity): Deferred<Boolean> = viewModelScope.async {
        saving { repository.updateCard(card) }
    }

    /** [save], then the cards read again; any failure in [CardsState.error] */
    private suspend fun saving(save: suspend () -> Unit): Boolean {
        try {
            save()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            set { it.copy(error = e.toString(), failedAction = null) }
            return false
        }
        // A failed read reports itself, and the save stands
        load()
        return true
    }

    /**
     * Move a card as a drag leaves it, with the indexes a Flutter
     * reorderable list reports: [newIndex] counts the card itself when
     * moving down. The new order shows at once; saving only follows, card
     * by card, for those whose position changed. A save that fails puts
     * the old order back, on screen and for the cards already saved.
     */
    fun reorderCards(oldIndex: Int, newIndex: Int): Job {
        val before = state.value.cards
        val cards = before.toMutableList()
        val target = if (newIndex > oldIndex) newIndex - 1 else newIndex
        // Where Dart would throw a RangeError into the framework's log
        if (oldIndex !in cards.indices || target !in cards.indices) return DONE
        val moved = cards.removeAt(oldIndex)
        cards.add(target, moved)

        val numbered = cards.mapIndexed { i, card -> card.copy(position = i) }
        // Shown at once; saving only follows
        set { it.copy(cards = numbered) }

        return viewModelScope.launch {
            val saved = mutableListOf<CardEntity>()
            try {
                numbered.forEachIndexed { i, card ->
                    if (cards[i].position != i) {
                        repository.updateCard(card)
                        saved.add(card)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                putBack(before, numbered, saved)
                set { it.copy(error = e.toString(), failedAction = CardAction.REORDER) }
            }
        }
    }

    /**
     * Undo a reorder that failed halfway: the cards [saved] get their old
     * position again, and [before] shows again while [shown], the new
     * order, is still on screen; otherwise what the disk holds does
     */
    private suspend fun putBack(before: List<CardEntity>, shown: List<CardEntity>, saved: List<CardEntity>) {
        val old = before.associateBy { it.internalId }
        try {
            for (card in saved) old[card.internalId]?.let { repository.updateCard(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The disk holds a mix of both: show what it holds
            load()
            return
        }
        if (state.value.cards === shown) set { it.copy(cards = before) } else load()
    }

    /** Delete every card numbered [cardId], then read them all again */
    fun deleteCardById(cardId: String): Job = viewModelScope.launch {
        try {
            repository.deleteCard(cardId)
            load()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            set { it.copy(error = e.toString(), failedAction = CardAction.DELETE) }
        }
    }

    /** Ask for the balance of the card numbered [cardId] */
    fun refreshCardBalance(cardId: String): Job {
        // Only one refresh at a time
        if (state.value.refreshingCardId != null) return DONE
        val card = state.value.cards.firstOrNull { it.id == cardId } ?: return DONE

        set {
            it.copy(
                refreshingCardId = cardId,
                error = null,
                failedAction = null,
                refreshError = null,
                refreshFailedCardId = null,
            )
        }
        return viewModelScope.launch {
            try {
                val balance = repository.refreshCardBalance(card)
                repository.updateCardBalance(cardId, balance.balance, balance.balanceDate)
                load()
                set { it.copy(lastRefreshSuccess = true, refreshingCardId = null) }
            } catch (e: ApiException) {
                // Keep the last known balance; flag the card as failed so
                // the screen can show the stale notice and a typed message
                set { it.copy(refreshError = e, refreshFailedCardId = cardId, refreshingCardId = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                set { it.copy(error = e.toString(), failedAction = null, refreshingCardId = null) }
            } finally {
                // Whatever ended it, the refresh is over
                if (state.value.refreshingCardId == cardId) set { it.copy(refreshingCardId = null) }
            }
        }
    }

    /**
     * Refresh every balance as the app opens, once per launch, without
     * announcing it: a card that fails only shows its balance may be out
     * of date.
     *
     * Returns false while there is nothing to refresh yet, so the caller
     * can try again once the cards have loaded.
     */
    fun refreshOnOpen(): Boolean {
        val current = state.value
        if (current.cards.isEmpty()) return false
        if (openRefreshDone) return true
        openRefreshDone = true
        if (current.isRefreshingAll || current.refreshingCardId != null) return true

        set { it.copy(isRefreshingAll = true) }
        viewModelScope.launch {
            refreshEach(current.cards, quietly = true)
        }
        return true
    }

    /** Refresh every card, one at a time so the service is not hammered */
    fun refreshAllBalances(): Job {
        val current = state.value
        if (current.isRefreshingAll || current.refreshingCardId != null) return DONE
        if (current.cards.isEmpty()) return DONE

        set { it.copy(isRefreshingAll = true, error = null, failedAction = null, refreshError = null, refreshFailedCardId = null) }
        return viewModelScope.launch {
            refreshEach(current.cards, quietly = false)
        }
    }

    /**
     * Ask for each of [cards] in turn. A quiet refresh, the one on opening,
     * only marks the first card that failed; the one the user asked for
     * also reports the error, or its success.
     */
    private suspend fun refreshEach(cards: List<CardEntity>, quietly: Boolean) {
        var failure: ApiException? = null
        var failedCardId: String? = null
        var ended = false
        try {
            for (card in cards) {
                try {
                    val balance = repository.refreshCardBalance(card)
                    repository.updateCardBalance(card.id, balance.balance, balance.balanceDate)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failure = failure ?: e as? ApiException ?: ApiException(e.toString())
                    failedCardId = failedCardId ?: card.id
                }
            }
            load()
            val failed = failedCardId
            set {
                if (quietly) {
                    it.copy(
                        isRefreshingAll = false,
                        refreshFailedCardId = failed,
                        // Every card answered: nothing is out of date any more
                        refreshError = if (failed == null) null else it.refreshError,
                    )
                } else {
                    it.copy(
                        isRefreshingAll = false,
                        refreshError = failure ?: it.refreshError,
                        refreshFailedCardId = failed ?: it.refreshFailedCardId,
                        lastRefreshSuccess = failure == null,
                    )
                }
            }
            ended = true
        } finally {
            // Whatever cut it short, the refresh is over
            if (!ended) set { it.copy(isRefreshingAll = false) }
        }
    }

    /** Clear the last refresh error and the failed card flag */
    fun clearRefreshError() = set { it.copy(refreshError = null, refreshFailedCardId = null) }

    fun clearError() = set { it.copy(error = null, failedAction = null) }

    companion object {
        private val DONE: Job = Job().apply { complete() }

        /** A random (version 4) UUID, as the Dart uuid package's v4 */
        private fun newInternalId(): String = UUID.randomUUID().toString()

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                CardsViewModel(
                    container.cardRepository,
                    ready = { container.startup.join() },
                    reloads = container.backup.imported,
                )
            }
        }
    }
}
