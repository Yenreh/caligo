package com.yenreh.caligo.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yenreh.caligo.R
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.data.datasources.CardNotFoundApiException
import com.yenreh.caligo.data.datasources.InvalidCardApiException
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.datasources.RateLimitApiException
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.cards.CardsState
import com.yenreh.caligo.ui.cards.CardsViewModel
import com.yenreh.caligo.ui.common.rememberShownOnScreen
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTone
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.stops.StopsViewModel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The home with its data: what the Flutter MainScreen decides beyond
 * drawing. Balances refresh once as the app opens, when the settings ask
 * for it; arrivals refresh only while the home is in view and lists stops.
 *
 * The view models come from the activity by default. [stopsViewModel] is
 * only asked for while the home lists stops, so a home of cards alone
 * never loads them.
 */
@Composable
fun HomeRoute(
    settings: Settings,
    modifier: Modifier = Modifier,
    actions: HomeActions = HomeActions(),
    cardsViewModel: CardsViewModel = viewModel(factory = CardsViewModel.Factory),
    stopsViewModel: @Composable () -> StopsViewModel = { viewModel(factory = StopsViewModel.Factory) },
) {
    val cards by cardsViewModel.cards.collectAsStateWithLifecycle()
    val stopsModel = if (settings.showsStops) stopsViewModel() else null
    val stops = stopsModel?.stops?.collectAsStateWithLifecycle()?.value
    val snackbars = rememberLabSnackbars()

    RefreshBalancesOnOpen(settings, cards, cardsViewModel)
    WatchArrivals(stopsModel)
    ListenCardRefresh(cardsViewModel, snackbars)

    HomeScreen(
        settings = settings,
        cards = cards,
        stops = stops,
        modifier = modifier,
        onRefreshCard = { cardsViewModel.refreshCardBalance(it) },
        onRefreshAllCards = { cardsViewModel.refreshAllBalances() },
        onRefreshStops = { stopsModel?.refreshArrivals(force = true) },
        actions = actions,
        snackbars = snackbars,
    )
}

/**
 * Every balance, once, as the app opens: not on coming back from the
 * background. Done once the settings and the cards have loaded.
 */
@Composable
private fun RefreshBalancesOnOpen(settings: Settings, cards: CardsState, viewModel: CardsViewModel) {
    val due = remember { Due() }
    LaunchedEffect(settings, cards.isLoading, cards.cards) {
        if (!due.pending || settings.isLoading) return@LaunchedEffect
        if (!settings.refreshBalancesOnOpen || !settings.showsCards) {
            due.pending = false
            return@LaunchedEffect
        }
        if (cards.isLoading) return@LaunchedEffect
        // False until the cards are there to refresh
        if (viewModel.refreshOnOpen()) due.pending = false
    }
}

/** Set on opening only; cleared once the balances have been dealt with */
private class Due {
    var pending = true
}

/**
 * Arrivals on their pace while the home is in view: no polling the
 * service in the background or under another screen. Back in view,
 * whatever changed meanwhile is asked for.
 */
@Composable
private fun WatchArrivals(viewModel: StopsViewModel?) {
    val shown = rememberShownOnScreen()
    DisposableEffect(viewModel, shown) {
        val home = Any()
        val watched = viewModel?.takeIf { shown }
        watched?.startAutoRefresh(home, homeOnly = true)
        watched?.refreshArrivals()
        onDispose { watched?.stopAutoRefresh(home) }
    }
}

/** A typed API error, as the user reads it */
private fun cardErrorMessage(error: ApiException): Int = when (error) {
    is NetworkApiException -> R.string.network_error_message
    is ServerApiException -> R.string.server_error_message
    is InvalidCardApiException -> R.string.invalid_card_message
    is CardNotFoundApiException -> R.string.card_not_found_message
    is RateLimitApiException -> R.string.rate_limit_message
    else -> R.string.could_not_update_balance
}

/** Report the outcome of a balance refresh, wherever the cards are shown */
@Composable
fun ListenCardRefresh(viewModel: CardsViewModel, snackbars: LabSnackbars) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbars, resources) {
        var previous: CardsState? = null
        viewModel.cards.collect { next ->
            if (next.lastRefreshSuccess && previous?.lastRefreshSuccess != true) {
                snackbars.show(resources.getString(R.string.balance_updated), LabTone.OK, duration = 1.seconds)
            }

            val refreshError = next.refreshError
            if (refreshError != null && refreshError != previous?.refreshError) {
                val failedCardId = next.refreshFailedCardId
                snackbars.show(
                    resources.getString(cardErrorMessage(refreshError)),
                    LabTone.CRIT,
                    actionLabel = failedCardId?.let { resources.getString(R.string.retry) },
                    onAction = { if (failedCardId != null) viewModel.refreshCardBalance(failedCardId) },
                )
                // Cleared after a beat, as the Flutter app does
                launch {
                    delay(100.milliseconds)
                    viewModel.clearRefreshError()
                }
            }

            if (next.error != null && next.error != previous?.error) {
                snackbars.show(resources.getString(R.string.could_not_update_balance), LabTone.CRIT, duration = 2.seconds)
                launch {
                    delay(100.milliseconds)
                    viewModel.clearError()
                }
            }
            previous = next
        }
    }
}
