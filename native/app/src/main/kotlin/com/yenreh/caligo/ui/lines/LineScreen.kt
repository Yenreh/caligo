package com.yenreh.caligo.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.rememberShownOnScreen
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.map.LabMap
import com.yenreh.caligo.ui.map.LabMapMarker
import com.yenreh.caligo.ui.map.LabMapPolyline
import com.yenreh.caligo.ui.map.MapCredits
import com.yenreh.caligo.ui.map.MapTileSource
import com.yenreh.caligo.ui.map.tilesPalette
import com.yenreh.caligo.ui.stops.Chevron
import com.yenreh.caligo.ui.stops.OpenStop
import com.yenreh.caligo.ui.stops.StopDetailsFor
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.LabPalette
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import kotlin.time.Duration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The line screen of [line] with its own view model, the hours from the
 * activity's [lines], [tiles] as the [settings] ask, and stop details
 * that save through the activity's [stops] and go to the trip planner
 * through [onGoHere]
 */
@Composable
fun LineRoute(
    line: String,
    settings: Settings,
    tiles: MapTileSource,
    stops: StopsViewModel,
    lines: LinesViewModel,
    onBack: () -> Unit,
    viewModel: LineViewModel = viewModel(factory = LineViewModel.factory(line)),
    onGoHere: ((TripPlace) -> Unit)? = null,
) {
    LaunchedEffect(lines) { lines.watchHours() }
    val hours by lines.hours.collectAsStateWithLifecycle()
    LineScreen(
        viewModel,
        hours = hours[line],
        tiles = tiles,
        darkTiles = settings.darkMap,
        carto = tiles.byCarto,
        onBack = onBack,
        details = { open, dismiss ->
            StopDetailsFor(open, stops, onDismiss = dismiss, fetchOnOpen = true, onGoHere = onGoHere)
        },
    )
}

/**
 * A line's route on the map, one direction at a time, with its buses
 * where they are now and its stops in order below.
 *
 * [hours] are the line's, when known. [details] shows a stop opened from
 * the map or the list, which asks for its buses as it opens: the route
 * says where the stop is, not what is coming. The buses are asked for
 * only while [shown].
 */
@Composable
fun LineScreen(
    viewModel: LineViewModel,
    hours: LineHours?,
    tiles: MapTileSource,
    darkTiles: Boolean,
    carto: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    details: @Composable (open: OpenStop, onDismiss: () -> Unit) -> Unit = { _, _ -> },
    shown: Boolean = rememberShownOnScreen(),
    /** What time it is, for whether the line runs now */
    now: () -> Long = System::currentTimeMillis,
    /** Tiles fade in as they come; screenshots turn it off */
    fadeIn: Boolean = true,
) {
    val p = LabTheme.palette
    val state by viewModel.screen.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<OpenStop?>(null) }

    // Out of view the buses are let go; back in view they are asked for at once
    DisposableEffect(viewModel, shown) {
        viewModel.setShown(shown)
        onDispose { viewModel.setShown(false) }
    }

    fun openStop(stop: LineStop) {
        viewModel.open(stop)
        open = OpenStop(
            NearbyStop(
                id = stop.stopId,
                name = stop.name,
                distanceMeters = 0.0,
                latitude = stop.latitude,
                longitude = stop.longitude,
            ),
            anchorLatitude = stop.latitude,
            anchorLongitude = stop.longitude,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LabTopBar(
                title = viewModel.line,
                onBack = onBack,
                actions = {
                    if (state.updatingBuses) {
                        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                Modifier
                                    .size(18.dp)
                                    .testTag("updating_buses"),
                                color = p.accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        RefreshButton(onClick = viewModel::refreshBuses)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (val stops = state.stops) {
                Loaded.Loading -> CircularProgressIndicator(Modifier.size(36.dp), color = p.accent, strokeWidth = 4.dp)
                is Loaded.Failed -> LabEmptyState(
                    icon = painterResource(R.drawable.ic_cloud_off),
                    // The Flutter screen said the arrivals failed
                    message = stringResource(R.string.could_not_load_route),
                    actionLabel = stringResource(R.string.retry),
                    onAction = viewModel::reloadStops,
                    secondaryAction = true,
                    modifier = Modifier.testTag("line_message"),
                )
                is Loaded.Ready if stops.value.isEmpty() -> LabEmptyState(
                    icon = painterResource(R.drawable.ic_route),
                    message = stringResource(R.string.no_line_route),
                    modifier = Modifier.testTag("line_message"),
                )
                is Loaded.Ready -> Route(
                    viewModel,
                    state,
                    stops.value,
                    hours,
                    tiles,
                    darkTiles,
                    carto,
                    now,
                    fadeIn,
                    onOpen = ::openStop,
                )
            }
        }
    }

    open?.let { stop -> details(stop) { open = null } }
}

/** The app bar's refresh, as a Flutter IconButton with its tooltip */
@Composable
private fun RefreshButton(onClick: () -> Unit) {
    val description = stringResource(R.string.update_bus_positions)
    LabTooltip(description) {
        Box(
            Modifier
                .size(48.dp)
                .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = LabTheme.palette.ink)
        }
    }
}

@Composable
private fun Route(
    viewModel: LineViewModel,
    state: LineScreenState,
    all: List<LineStop>,
    hours: LineHours?,
    tiles: MapTileSource,
    darkTiles: Boolean,
    carto: Boolean,
    now: () -> Long,
    fadeIn: Boolean,
    onOpen: (LineStop) -> Unit,
) {
    val p = LabTheme.palette
    val tilesPalette = tilesPalette(dark = darkTiles)
    val route = remember(all, state.chosenDirection) { ShownRoute(all, state.chosenDirection) }
    val direction = route.direction
    val buses = state.buses.valueOrNull
    var creditsOpen by remember { mutableStateOf(false) }
    // A long press on a bus, by marker id, counted so each one shows its name again
    var held by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val status = listOfNotNull(
        hours?.let {
            if (it.runsAt(now())) {
                stringResource(R.string.line_runs, hhmm(it.start), hhmm(it.end))
            } else {
                "${stringResource(R.string.line_not_running)} · ${hhmm(it.start)}–${hhmm(it.end)}"
            }
        },
        buses?.let { running -> busesThisWay(running.count { it.direction == direction }) },
    ).joinToString(" · ")

    // Buses going the other way would read as coming; those the service
    // could not place show faded rather than not at all
    val shownBuses = buses.orEmpty().filter { it.direction == null || it.direction == direction }
    val busIds = remember(shownBuses) { markerIds(shownBuses) }

    Column(Modifier.fillMaxSize()) {
        if (route.directions.size > 1) {
            DirectionToggle(
                labels = route.ends.map { stringResource(R.string.towards, it) },
                selected = route.directions.indexOf(direction),
                onSelected = { viewModel.chooseDirection(route.directions[it]) },
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
        }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                style = LabText.statusLine(p),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
                    .testTag("line_status"),
            )
        }

        Box(
            Modifier
                .weight(3f)
                .fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val map = viewModel.mapFor(DpSize(maxWidth, maxHeight))

                // Moving the map puts the credits away, as tapping elsewhere would
                LaunchedEffect(map) {
                    snapshotFlow { map.camera.center to map.camera.zoom }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect { creditsOpen = false }
                }

                val stopMarkers = route.stops.mapIndexed { i, stop ->
                    LabMapMarker("$STOP$i", GeoPoint(stop.latitude, stop.longitude), zIndex = 0f)
                }
                val busMarkers = shownBuses.mapIndexed { i, bus ->
                    LabMapMarker(busIds[i], GeoPoint(bus.latitude, bus.longitude), zIndex = 1f)
                }
                val byId = busIds.zip(shownBuses).toMap()
                val points = route.points
                LabMap(
                    state = map,
                    tiles = tiles,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("map"),
                    markers = remember(stopMarkers, busMarkers) { stopMarkers + busMarkers },
                    markerContent = { id ->
                        val stop = stopAt(id, route.stops)
                        val bus = byId[id]
                        when {
                            stop != null -> RouteStopDot(
                                selected = stop.stopId == state.selectedStopId,
                                tiles = tilesPalette,
                                modifier = Modifier.testTag("route_stop_${stop.stopId}"),
                            )
                            bus != null -> BusMarker(
                                bus = bus,
                                tiles = tilesPalette,
                                held = held?.takeIf { it.first == id }?.second ?: 0,
                                modifier = Modifier.testTag("bus_${bus.busNumber}"),
                            )
                        }
                    },
                    onMarkerTap = { id -> stopAt(id, route.stops)?.let(onOpen) },
                    onMarkerLongPress = { id ->
                        if (id in byId) held = id to (held?.second ?: 0) + 1
                    },
                    polylines = remember(points, tilesPalette) {
                        listOf(
                            LabMapPolyline(
                                id = "route",
                                points = points,
                                color = tilesPalette.accent,
                                width = 4.dp,
                                borderColor = tilesPalette.paper,
                                borderWidth = 1.5.dp,
                            ),
                        )
                    },
                    fadeIn = fadeIn,
                )
            }

            MapCredits(
                open = creditsOpen,
                onToggle = { creditsOpen = !creditsOpen },
                tiles = tilesPalette,
                carto = carto,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(p.ink),
        )

        val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        LazyColumn(
            Modifier
                .weight(2f)
                .fillMaxWidth()
                .testTag("line_stops"),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            itemsIndexed(route.stops) { i, stop ->
                if (i > 0) LabDivider()
                RouteStopRow(stop, selected = stop.stopId == state.selectedStopId, onClick = { onOpen(stop) })
            }
        }
    }
}

/** "1 bus this way", with the Flutter string's own words for none */
@Composable
private fun busesThisWay(count: Int): String =
    if (count == 0) {
        stringResource(R.string.no_buses_this_way)
    } else {
        pluralStringResource(R.plurals.buses_this_way, count, count)
    }

/** A stop along the route, numbered; picked, it stands out on paper2 in accent */
@Composable
private fun RouteStopRow(stop: LineStop, selected: Boolean, onClick: () -> Unit) {
    val p = LabTheme.palette
    val tone = if (selected) p.accent else null
    LabListTile(
        title = stop.name,
        contentColor = tone,
        leadingContent = {
            Text(
                text = "${stop.sequence}",
                textAlign = TextAlign.End,
                style = LabText.mono(p, size = 13f, color = p.muted),
                modifier = Modifier.width(28.dp),
            )
        },
        modifier = Modifier
            .then(if (selected) Modifier.background(p.paper2) else Modifier)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("route_stop_row"),
        trailing = { Chevron(tone ?: p.muted) },
    )
}

/** Two-way switch between a line's directions, named by where each ends */
@Composable
private fun DirectionToggle(labels: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, p.rule)
            .padding(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val chosen = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (chosen) p.fill else p.paper)
                    .clickable(enabled = !chosen, role = Role.Button) { onSelected(i) }
                    .semantics { this.selected = chosen }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .testTag("direction_$i"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge.copy(color = if (chosen) p.onFill else p.ink),
                )
            }
        }
    }
}

/** Small square for a stop along the route; larger and filled once picked */
@Composable
private fun RouteStopDot(selected: Boolean, tiles: LabPalette, modifier: Modifier = Modifier) {
    Box(modifier.size(MARKER_SIZE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(if (selected) 18.dp else 12.dp)
                .background(if (selected) tiles.accent else tiles.paper)
                .border(2.dp, tiles.ink),
        )
    }
}

/**
 * A bus on the line: filled square with a bus in it, apart from the
 * hollow stops. Faded when the service could not say which way it goes;
 * its number shows as a tooltip each time [held] grows, as a long press
 * on a Flutter tooltip does. The map takes the press, so the tooltip
 * takes no input of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusMarker(bus: LineBus, tiles: LabPalette, held: Int, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    val tooltip = rememberTooltipState()
    LaunchedEffect(held) { if (held > 0) tooltip.show() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip(shape = RectangleShape, containerColor = p.ink, contentColor = p.bg) {
                Text(bus.busNumber, style = MaterialTheme.typography.bodySmall.copy(color = p.bg))
            }
        },
        state = tooltip,
        enableUserInput = false,
    ) {
        Box(
            modifier
                .size(MARKER_SIZE)
                .alpha(if (bus.direction == null) 0.45f else 1f)
                .semantics { contentDescription = bus.busNumber },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(tiles.fill)
                    .border(1.5.dp, tiles.paper),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_directions_bus_rounded),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = tiles.onFill,
                )
            }
        }
    }
}

/** A time of day as the Flutter screens write it, "04:23" */
fun hhmm(time: Duration): String =
    "${(time.inWholeHours % 24).toString().padStart(2, '0')}:" +
        (time.inWholeMinutes % 60).toString().padStart(2, '0')

/** The stop of a route a marker id names, if it names one */
private fun stopAt(id: String, stops: List<LineStop>): LineStop? =
    id.removePrefix(STOP).takeIf { id.startsWith(STOP) }?.toIntOrNull()?.let { stops.getOrNull(it) }

/** One marker id per bus, by its number; a number seen again gets its place too */
private fun markerIds(buses: List<LineBus>): List<String> {
    val seen = HashMap<String, Int>()
    return buses.map { bus ->
        val n = (seen[bus.busNumber] ?: 0) + 1
        seen[bus.busNumber] = n
        if (n == 1) "$BUS${bus.busNumber}" else "$BUS${bus.busNumber}#$n"
    }
}

private const val STOP = "stop:"
private const val BUS = "bus:"

/** Both kinds of marker are laid out in a box this size, as the Flutter markers */
private val MARKER_SIZE = 28.dp
