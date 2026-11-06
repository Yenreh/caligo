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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The saved cards and how their balance refreshes are going */
data class CardsState(
    val cards: List<CardEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
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
 * The cards and their balances: the part of the Flutter app's
 * CardsNotifier the home screen uses. Creating, editing, reordering,
 * deleting and the backup screens come with the cards and settings
 * screens.
 *
 * One per activity, shared by the screens that list cards. Each action
 * returns the [Job] doing it, as the Dart methods return their Future.
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
        set { it.copy(isLoading = true, error = null) }
        try {
            val cards = repository.getAllCards()
            set { it.copy(cards = cards, isLoading = false) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            set { it.copy(error = e.toString(), isLoading = false) }
        }
    }

    /** Ask for the balance of the card numbered [cardId] */
    fun refreshCardBalance(cardId: String): Job {
        // Only one refresh at a time
        if (state.value.refreshingCardId != null) return DONE
        val card = state.value.cards.firstOrNull { it.id == cardId } ?: return DONE

        set {
            it.copy(refreshingCardId = cardId, error = null, refreshError = null, refreshFailedCardId = null)
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
                set { it.copy(error = e.toString(), refreshingCardId = null) }
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

        set { it.copy(isRefreshingAll = true, error = null, refreshError = null, refreshFailedCardId = null) }
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

    fun clearError() = set { it.copy(error = null) }

    companion object {
        private val DONE: Job = Job().apply { complete() }

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
