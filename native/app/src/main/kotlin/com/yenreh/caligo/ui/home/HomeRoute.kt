package com.yenreh.caligo.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.cards.CardsViewModel
import com.yenreh.caligo.ui.cards.ListenCardRefresh
import com.yenreh.caligo.ui.cards.RefreshBalancesOnOpen
import com.yenreh.caligo.ui.common.rememberShownOnScreen
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.stops.StopsViewModel

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
