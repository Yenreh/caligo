package com.yenreh.caligo.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.AddressMatch
import com.yenreh.caligo.domain.AddressPrecision
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.lab.LabTone
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.labBottomInset
import com.yenreh.caligo.ui.lab.labSideInsets
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.stops.Chevron
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * The place picker for one end of a trip, [origin] or not, with its own
 * [viewModel], the favorites and stations of the activity's [stops].
 * [onPicked] hands the place chosen back; [onPickOnMap] opens the map,
 * whose point comes back through [picked], taken as this screen shows
 * again and named here.
 */
@Composable
fun PlacePickerRoute(
    origin: Boolean,
    stops: StopsViewModel,
    picked: PickedPlaces,
    onBack: () -> Unit,
    onPicked: (TripPlace) -> Unit,
    onPickOnMap: () -> Unit,
    viewModel: PlacePickerViewModel,
) {
    val point = picked.point
    LaunchedEffect(point) { picked.takePoint()?.let { onPicked(viewModel.pointPlace(it)) } }
    // The catalog, as watching the Flutter stationsProvider loads it
    LaunchedEffect(stops) { stops.watchStations() }
    val favorites by stops.stops.collectAsStateWithLifecycle()
    val stations by stops.stations.collectAsStateWithLifecycle()
    PlacePickerScreen(
        viewModel,
        title = stringResource(if (origin) R.string.choose_origin else R.string.choose_destination),
        favorites = favorites.favorites,
        // A catalog that fails lists no station, as Dart's asData ?? []
        stations = (stations as? Loaded.Ready)?.value.orEmpty(),
        onBack = onBack,
        onPicked = onPicked,
        onPickOnMap = onPickOnMap,
    )
}

/**
 * Choose where a trip starts or ends: the device, a point on the map, a
 * favorite, a station, any stop along the routes, or an address or place
 * looked up on request, since each search asks Google through the phone.
 */
@Composable
fun PlacePickerScreen(
    viewModel: PlacePickerViewModel,
    title: String,
    favorites: List<FavoriteStop>,
    stations: List<Station>,
    onBack: () -> Unit,
    onPicked: (TripPlace) -> Unit,
    onPickOnMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LabTheme.palette
    val state by viewModel.screen.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val network = routes.network
    val snackbars = rememberLabSnackbars()
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val picked by rememberUpdatedState(onPicked)

    // The box as typed, saved with the screen, is what the lists filter by
    LaunchedEffect(viewModel, query) { snapshotFlow { query.text.toString() }.collect(viewModel::setQuery) }
    LaunchedEffect(focus) { focus.requestFocus() }

    val couldNotLocate = stringResource(R.string.could_not_locate)
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is PlacePickerEvent.Picked -> picked(it.place)
                PlacePickerEvent.CouldNotLocate -> snackbars.show(couldNotLocate, LabTone.WARN)
            }
        }
    }

    val filter = state.filter
    val shownFavorites = filter.favorites(favorites)
    val shownStations = filter.stations(stations)
    val shownStops = remember(state.typed, network) { filter.stops(network) }
    val bottom = labBottomInset()

    // Read here, as LazyListScope builds rows without composing
    val searchAsAddress = stringResource(R.string.search_as_address, state.typed)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) },
        topBar = { LabTopBar(title = title, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .windowInsetsPadding(labSideInsets()),
        ) {
            LabTextField(
                state = query,
                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("place_search"),
                hint = stringResource(R.string.search_place),
                leadingIcon = painterResource(R.drawable.ic_search),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { viewModel.findAddress() },
            )
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("places"),
                contentPadding = PaddingValues(bottom = bottom),
            ) {
                if (state.offersAddress) {
                    if (state.showsFound) {
                        section("addresses", R.string.addresses_section, if (state.found.isEmpty()) listOf(null) else state.found) { match ->
                            if (match == null) NotFoundTile() else AddressTile(match, onClick = { picked(match.place) })
                        }
                    } else {
                        item(key = "search_address") {
                            LabListTile(
                                title = searchAsAddress,
                                leading = painterResource(R.drawable.ic_travel_explore),
                                modifier = Modifier
                                    .clickable(enabled = !state.searching) { viewModel.findAddress() }
                                    .testTag("search_address"),
                                trailing = if (state.searching) ({ Spinner("searching") }) else null,
                            )
                        }
                    }
                    item(key = "address_rule") { LabDivider() }
                }
                item(key = "device") {
                    LabListTile(
                        title = stringResource(R.string.my_location),
                        leading = painterResource(R.drawable.ic_my_location),
                        modifier = Modifier
                            .clickable(enabled = !state.locating) { viewModel.useDevice() }
                            .testTag("use_device"),
                        trailing = if (state.locating) ({ Spinner("locating") }) else null,
                    )
                }
                item(key = "device_rule") { LabDivider() }
                item(key = "map") {
                    LabListTile(
                        title = stringResource(R.string.pick_on_map),
                        leading = painterResource(R.drawable.ic_map),
                        modifier = Modifier
                            .clickable(onClick = onPickOnMap)
                            .testTag("pick_on_map"),
                        trailing = { Chevron() },
                    )
                }
                section("favorites", R.string.favorites_short, shownFavorites) { favorite ->
                    PlaceTile(
                        icon = R.drawable.ic_signpost,
                        title = favorite.displayName,
                        subtitle = favorite.secondaryName,
                        onClick = { picked(favoritePlace(favorite, network)) },
                    )
                }
                section("stations", R.string.station_catalog, shownStations) { station ->
                    PlaceTile(
                        icon = R.drawable.ic_directions_bus,
                        title = station.name,
                        subtitle = station.address,
                        onClick = { picked(TripPlace(station.latitude, station.longitude, station.name)) },
                    )
                }
                section("stops", R.string.stops_section, shownStops) { stop ->
                    PlaceTile(
                        icon = R.drawable.ic_place,
                        title = stop.name,
                        onClick = { picked(TripPlace(stop.latitude, stop.longitude, stop.name)) },
                    )
                }
            }
        }
    }
}

/** What an address found names and where it is */
private val AddressMatch.place: TripPlace get() = TripPlace(latitude, longitude, name)

/** A section of [rows], under its label, with a rule between rows; nothing without rows */
private fun <T> LazyListScope.section(key: String, title: Int, rows: List<T>, row: @Composable (T) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "$key:label") {
        LabSectionLabel(
            stringResource(title),
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 6.dp),
        )
    }
    for ((i, value) in rows.withIndex()) {
        item(key = "$key:$i") {
            Column {
                if (i > 0) LabDivider()
                row(value)
            }
        }
    }
}

@Composable
private fun PlaceTile(icon: Int, title: String, onClick: () -> Unit, subtitle: String? = null) {
    LabListTile(
        title = title,
        subtitle = subtitle?.takeIf { it.isNotEmpty() },
        leading = painterResource(icon),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("place_tile"),
    )
}

@Composable
private fun AddressTile(match: AddressMatch, onClick: () -> Unit) {
    PlaceTile(
        icon = if (match.precision == AddressPrecision.STOP) R.drawable.ic_place else R.drawable.ic_location_on,
        title = match.name,
        subtitle = when (match.precision) {
            AddressPrecision.EXACT -> null
            AddressPrecision.PLACE -> match.detail
            AddressPrecision.CROSSING -> stringResource(R.string.address_crossing)
            AddressPrecision.STOP -> stringResource(R.string.address_near_stop)
        },
        onClick = onClick,
    )
}

@Composable
private fun NotFoundTile() {
    LabListTile(
        title = stringResource(R.string.address_not_found),
        leading = painterResource(R.drawable.ic_search_off),
        titleStyle = LabText.statusLine(LabTheme.palette),
        modifier = Modifier.testTag("address_not_found"),
    )
}

@Composable
private fun Spinner(tag: String) {
    Box(Modifier.size(18.dp)) {
        CircularProgressIndicator(
            Modifier
                .size(18.dp)
                .testTag(tag),
            color = LabTheme.palette.accent,
            strokeWidth = 2.dp,
        )
    }
}
