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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.cards.CardsRoute
import com.yenreh.caligo.ui.cards.CardsViewModel
import com.yenreh.caligo.ui.cards.CreateCardRoute
import com.yenreh.caligo.ui.cards.EditCardRoute
import com.yenreh.caligo.ui.home.HomeActions
import com.yenreh.caligo.ui.home.HomeRoute
import com.yenreh.caligo.ui.licenses.LicenseRoute
import com.yenreh.caligo.ui.licenses.LicensesRoute
import com.yenreh.caligo.ui.lines.LineRoute
import com.yenreh.caligo.ui.lines.LineViewModel
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.map.MapTileSource
import com.yenreh.caligo.ui.map.rememberAppMapTiles
import com.yenreh.caligo.ui.settings.SettingsRoute
import com.yenreh.caligo.ui.stops.AddStopScreen
import com.yenreh.caligo.ui.stops.MapStopsRoute
import com.yenreh.caligo.ui.stops.StationStopsScreen
import com.yenreh.caligo.ui.stops.StopsRoute
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.trips.MapPointPicker
import com.yenreh.caligo.ui.trips.PickedPlace
import com.yenreh.caligo.ui.trips.PickedPlaces
import com.yenreh.caligo.ui.trips.PlacePickerRoute
import com.yenreh.caligo.ui.trips.PlacePickerViewModel
import com.yenreh.caligo.ui.trips.PlanTripRoute
import com.yenreh.caligo.ui.trips.PlanTripViewModel

/**
 * The app's screens on one back stack, the home at its root; back, and
 * the predictive back gesture, pop it.
 *
 * Each screen keeps its own view models, cleared when it is popped, but
 * for the cards, the stops and the lines: those are the activity's,
 * shared by every screen that lists them, as the Flutter providers are.
 *
 * The trip planner's pickers hand their choice back through
 * [PickedPlaces], saved with the back stack: the place picker's place to
 * the planner, the map's point to the place picker, each taken as the
 * screen below shows again.
 */
@Composable
fun AppNavigation(
    settings: Settings,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(Route.Home),
    /** The tiles of the maps, as the settings ask: the app's, or a test's */
    mapTiles: @Composable (Settings) -> MapTileSource = { rememberAppMapTiles(it) },
    /** The view model of a line's screen, by line name: its own, or a test's */
    lineViewModel: @Composable (line: String) -> LineViewModel = { viewModel(factory = LineViewModel.factory(it)) },
    /** The view model of a trip planner, with the activity's lines and where to go: its own, or a test's */
    planTripViewModel: @Composable (lines: LinesViewModel, destination: TripPlace?) -> PlanTripViewModel = { lines, destination ->
        viewModel(factory = PlanTripViewModel.factory(lines, destination))
    },
    /** The view model of a place picker: its own, or a test's */
    placePickerViewModel: @Composable () -> PlacePickerViewModel = { viewModel(factory = PlacePickerViewModel.Factory) },
) {
    val activity: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    // Read inside the entries, so they follow every change of the settings
    val current by rememberUpdatedState(settings)
    val picked = rememberSaveable(saver = PickedPlaces.Saver) { PickedPlaces() }
    fun open(route: Route) {
        backStack.add(route)
    }
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        // A choice the screen below was closed before taking goes with it
        picked.forget(planner = backStack.any { it is Route.PlanTrip }, picker = backStack.any { it is Route.PlacePicker })
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

    @Composable
    fun lines(): LinesViewModel = viewModel(activity, factory = LinesViewModel.Factory)

    fun station(station: Station) =
        open(Route.Station(station.id, station.name, station.address, station.latitude, station.longitude))

    // "Go here" of a stop's details
    fun goTo(place: TripPlace) = open(Route.PlanTrip(place.key))

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
                        onPlan = { open(Route.PlanTrip()) },
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
                AddStopScreen(
                    stops(),
                    lines(),
                    onBack = ::back,
                    onStation = ::station,
                    onLine = { open(Route.Line(it.id, it.name)) },
                    map = { MapStopsRoute(current, mapTiles(current), it) },
                    onGoHere = ::goTo,
                )
            }
            entry<Route.SearchStops> {
                AddStopScreen(
                    stops(),
                    lines(),
                    onBack = ::back,
                    onStation = ::station,
                    onLine = { open(Route.Line(it.id, it.name)) },
                    explore = true,
                    map = { MapStopsRoute(current, mapTiles(current), it) },
                    onGoHere = ::goTo,
                )
            }
            entry<Route.Line> { key ->
                LineRoute(
                    key.name,
                    current,
                    mapTiles(current),
                    stops(),
                    lines(),
                    onBack = ::back,
                    viewModel = lineViewModel(key.name),
                    onGoHere = ::goTo,
                )
            }
            entry<Route.Station> { key ->
                StationStopsScreen(
                    Station(key.id, key.name, key.address, key.latitude, key.longitude),
                    stops(),
                    onBack = ::back,
                    onDone = { backFrom(key) },
                    onGoHere = ::goTo,
                )
            }
            entry<Route.PlanTrip> { key ->
                PlanTripRoute(
                    settings = current,
                    tiles = mapTiles(current),
                    picked = picked,
                    onBack = ::back,
                    onPick = { origin, near -> open(Route.PlacePicker(origin, near?.key)) },
                    viewModel = planTripViewModel(lines(), key.destination?.place),
                )
            }
            entry<Route.PlacePicker> { key ->
                PlacePickerRoute(
                    origin = key.origin,
                    stops = stops(),
                    picked = picked,
                    onBack = ::back,
                    onPicked = { place ->
                        // Only while on top: a second tap, or a back already given, picks nothing
                        if (backStack.lastOrNull() == key) {
                            picked.place = PickedPlace(key.origin, place)
                            back()
                        }
                    },
                    onPickOnMap = { open(Route.MapPoint(key.near)) },
                    viewModel = placePickerViewModel(),
                )
            }
            entry<Route.MapPoint> { key ->
                val tiles = mapTiles(current)
                MapPointPicker(
                    near = key.near?.place,
                    tiles = tiles,
                    darkTiles = current.darkMap,
                    carto = tiles.byCarto,
                    onBack = ::back,
                    onUse = { point ->
                        if (backStack.lastOrNull() == key) {
                            picked.point = point
                            back()
                        }
                    },
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
