package com.yenreh.caligo.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.yenreh.caligo.BuildConfig
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.cards.CardsRoute
import com.yenreh.caligo.ui.cards.CardsViewModel
import com.yenreh.caligo.ui.cards.CreateCardRoute
import com.yenreh.caligo.ui.cards.EditCardRoute
import com.yenreh.caligo.ui.home.HomeActions
import com.yenreh.caligo.ui.home.HomeRoute
import com.yenreh.caligo.ui.licenses.LicenseRoute
import com.yenreh.caligo.ui.licenses.LicensesRoute
import com.yenreh.caligo.ui.settings.SettingsRoute
import com.yenreh.caligo.ui.stops.AddStopScreen
import com.yenreh.caligo.ui.stops.StationStopsScreen
import com.yenreh.caligo.ui.stops.StopsRoute
import com.yenreh.caligo.ui.stops.StopsViewModel

/**
 * The app's screens on one back stack, the home at its root; back, and
 * the predictive back gesture, pop it.
 *
 * Each screen keeps its own view models, cleared when it is popped, but
 * for the cards and the stops: those are the activity's, shared by every
 * screen that lists them, as the Flutter providers are.
 *
 * The planner and the line screens come in later parts: their keys
 * exist, nothing pushes them yet.
 */
@Composable
fun AppNavigation(
    settings: Settings,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(Route.Home),
) {
    val activity: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    // Read inside the entries, so they follow every change of the settings
    val current by rememberUpdatedState(settings)
    fun open(route: Route) {
        backStack.add(route)
    }
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    // A screen closing itself once its work is done: only while it is on
    // top, so a back the user already gave is not taken twice
    fun backFrom(route: Route) {
        if (backStack.lastOrNull() == route) back()
    }
    @Composable
    fun cards(): CardsViewModel = viewModel(activity, factory = CardsViewModel.Factory)

    @Composable
    fun stops(): StopsViewModel = viewModel(activity, factory = StopsViewModel.Factory)

    fun station(station: Station) =
        open(Route.Station(station.id, station.name, station.address, station.latitude, station.longitude))

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = ::back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { zoomIn() },
        popTransitionSpec = { zoomOut() },
        entryProvider = entryProvider {
            entry<Route.Home> {
                HomeRoute(
                    settings = current,
                    actions = HomeActions(
                        onSettings = { open(Route.Settings) },
                        onCards = { open(Route.Cards) },
                        onSearch = { open(Route.SearchStops) },
                        onStops = { open(Route.Stops) },
                    ),
                    cardsViewModel = cards(),
                    stopsViewModel = { stops() },
                )
            }
            entry<Route.Stops> {
                StopsRoute(current, stops(), onBack = ::back, onAdd = { open(Route.AddStop) })
            }
            entry<Route.AddStop> {
                AddStopScreen(stops(), onBack = ::back, onStation = ::station)
            }
            entry<Route.SearchStops> {
                AddStopScreen(stops(), onBack = ::back, onStation = ::station, explore = true)
            }
            entry<Route.Station> { key ->
                StationStopsScreen(
                    Station(key.id, key.name, key.address, key.latitude, key.longitude),
                    stops(),
                    onBack = ::back,
                    onDone = { backFrom(key) },
                )
            }
            entry<Route.Cards> {
                CardsRoute(
                    settings = current,
                    viewModel = cards(),
                    onBack = ::back,
                    onCreate = { open(Route.CreateCard) },
                    onEdit = { open(Route.EditCard(it)) },
                )
            }
            entry<Route.CreateCard> {
                CreateCardRoute(cards(), onBack = ::back, onDone = { backFrom(Route.CreateCard) })
            }
            entry<Route.EditCard> { key ->
                EditCardRoute(key.cardId, cards(), onBack = ::back, onDone = { backFrom(key) })
            }
            entry<Route.Settings> {
                SettingsRoute(onBack = ::back, onLicenses = { open(Route.Licenses) })
            }
            entry<Route.Licenses> {
                LicensesRoute(
                    appVersion = BuildConfig.VERSION_NAME,
                    onBack = ::back,
                    onPackage = { open(Route.PackageLicense(it)) },
                )
            }
            entry<Route.PackageLicense> { key ->
                LicenseRoute(key.name, onBack = ::back)
            }
        },
    )
}

/**
 * Close to Flutter's zoom page transition, its default on Android: the
 * new screen grows into place as it fades in, the one below grows a
 * little behind it
 */
private fun zoomIn(): ContentTransform =
    (fadeIn(tween(FADE_MS, delayMillis = FADE_DELAY_MS)) + scaleIn(tween(ZOOM_MS, easing = FastOutSlowInEasing), 0.85f))
        .togetherWith(scaleOut(tween(ZOOM_MS, easing = FastOutSlowInEasing), 1.05f))

/** The way back: the screen shrinks and fades away over the one below */
private fun zoomOut(): ContentTransform =
    (scaleIn(tween(ZOOM_MS, easing = FastOutSlowInEasing), 1.05f))
        .togetherWith(fadeOut(tween(FADE_MS, delayMillis = FADE_DELAY_MS)) + scaleOut(tween(ZOOM_MS), 0.9f))
        .apply { targetContentZIndex = -1f }

private const val ZOOM_MS = 300
private const val FADE_MS = 75
private const val FADE_DELAY_MS = 25
