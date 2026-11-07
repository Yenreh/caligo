package com.yenreh.caligo.ui.trips

import android.content.ActivityNotFoundException
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.entities.WalkLeg
import com.yenreh.caligo.ui.common.rememberShownOnScreen
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.lab.LabLineChip
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.lab.LabTone
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.map.LabMap
import com.yenreh.caligo.ui.map.LabMapMarker
import com.yenreh.caligo.ui.map.LabMapPolyline
import com.yenreh.caligo.ui.map.MapCredits
import com.yenreh.caligo.ui.map.MapTileSource
import com.yenreh.caligo.ui.map.tilesPalette
import com.yenreh.caligo.ui.theme.LabPalette
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The trip planner with its own [viewModel], [tiles] as the [settings]
 * ask. [onPick] opens the place picker for one end, near the place it
 * should open on; what it chooses comes back through [picked], taken as
 * this screen shows again.
 */
@Composable
fun PlanTripRoute(
    settings: Settings,
    tiles: MapTileSource,
    picked: PickedPlaces,
    onBack: () -> Unit,
    onPick: (origin: Boolean, near: TripPlace?) -> Unit,
    viewModel: PlanTripViewModel,
) {
    val place = picked.place
    LaunchedEffect(place) { picked.takePlace()?.let { viewModel.choose(it.origin, it.place) } }
    PlanTripScreen(
        viewModel,
        tiles = tiles,
        darkTiles = settings.darkMap,
        carto = tiles.byCarto,
        onBack = onBack,
        onChoose = { origin -> onPick(origin, viewModel.pickerNear(origin)) },
    )
}

/**
 * From one place to another on the MIO: where to walk, which buses to
 * take and where to change, with the trip drawn on the map.
 *
 * Origin and destination open the picker through [onChoose]; each option
 * lists its legs and, once opened, its steps, with the map above it. The
 * arrivals are asked for only while [shown]. [darkTiles] says the tiles
 * are dark, which the controls on them follow; [carto] that they are
 * CARTO's, which the credits then name.
 */
@Composable
fun PlanTripScreen(
    viewModel: PlanTripViewModel,
    tiles: MapTileSource,
    darkTiles: Boolean,
    carto: Boolean,
    onBack: () -> Unit,
    onChoose: (origin: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shown: Boolean = rememberShownOnScreen(),
    /** What time it is, for the minutes to the first bus */
    now: () -> Long = System::currentTimeMillis,
    /** Tiles fade in as they come; screenshots turn it off */
    fadeIn: Boolean = true,
) {
    val p = LabTheme.palette
    val state by viewModel.screen.collectAsStateWithLifecycle()
    val snackbars = rememberLabSnackbars()
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var creditsOpen by remember { mutableStateOf(false) }

    // Out of view nothing is asked for; back in view the open trip catches up
    DisposableEffect(viewModel, shown) {
        viewModel.setShown(shown)
        onDispose { viewModel.setShown(false) }
    }

    val couldNotLocate = stringResource(R.string.could_not_locate)
    val couldNotPlan = stringResource(R.string.could_not_plan)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect {
            when (it) {
                PlanTripMessage.COULD_NOT_LOCATE -> snackbars.show(couldNotLocate, LabTone.WARN)
                PlanTripMessage.COULD_NOT_PLAN -> snackbars.show(couldNotPlan, LabTone.CRIT)
            }
        }
    }

    fun toggle(row: OptionRow, listIndex: Int) {
        val opening = !row.expanded
        creditsOpen = false
        viewModel.toggle(row.index)
        if (!opening) return
        // The map appearing above pushes the list down: the opened row goes
        // to the top, its steps below it
        scope.launch {
            withFrameNanos {}
            list.animateScrollToItem(listIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) },
        topBar = {
            LabTopBar(
                title = stringResource(R.string.plan_trip),
                onBack = onBack,
                actions = {
                    if (state.canRefresh) {
                        RefreshTimesButton(fetching = state.fetchingLive, onClick = { viewModel.refreshLive(force = true) })
                    }
                    val from = state.from
                    val to = state.to
                    LabTooltip(stringResource(R.string.open_in_google_maps)) {
                        LabTextButton(
                            onClick = {
                                if (from != null && to != null) {
                                    try {
                                        links.openUri(googleMapsDirections(from, to))
                                    } catch (_: IllegalArgumentException) {
                                        // As launchUrl, nothing happens where nothing opens it
                                    } catch (_: ActivityNotFoundException) {
                                        // The same, from older handlers
                                    }
                                }
                            },
                            enabled = from != null && to != null,
                            modifier = Modifier.testTag("google_maps"),
                        ) {
                            Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.google_maps))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
        ) {
            PlacesPanel(
                from = state.from,
                to = state.to,
                locating = state.locating,
                onLocate = { viewModel.locate(force = true) },
                onFrom = { onChoose(true) },
                onTo = { onChoose(false) },
                canSwap = state.canSwap,
                onSwap = viewModel::swap,
            )
            val open = state.open
            if (open != null) {
                Rule(1.dp, p.rule)
                TripMap(
                    viewModel = viewModel,
                    option = open,
                    from = state.from,
                    to = state.to,
                    tiles = tiles,
                    darkTiles = darkTiles,
                    carto = carto,
                    creditsOpen = creditsOpen,
                    onCredits = { creditsOpen = it },
                    fadeIn = fadeIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(windowHeight() * MAP_SHARE),
                )
            }
            Rule(2.dp, p.ink)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Results(
                    results = state.results,
                    list = list,
                    now = now,
                    onRetry = viewModel::retryNetwork,
                    onToggle = ::toggle,
                    onChooseLine = viewModel::chooseLine,
                )
            }
        }
    }
}

/** Share of the screen the map takes while an option is open: the steps below it matter as much */
private const val MAP_SHARE = 0.3f

/** The height of the window, system bars included, as Flutter's MediaQuery size */
@Composable
private fun windowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

@Composable
private fun Rule(height: Dp, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(color),
    )
}

/** The app bar's refresh, as a Flutter IconButton: a spinner, and no taps, while the times come */
@Composable
private fun RefreshTimesButton(fetching: Boolean, onClick: () -> Unit) {
    val p = LabTheme.palette
    val description = stringResource(R.string.refresh_times)
    LabTooltip(description) {
        Box(
            Modifier
                .size(48.dp)
                .clickable(enabled = !fetching, onClickLabel = description, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (fetching) {
                CircularProgressIndicator(
                    Modifier
                        .size(18.dp)
                        .testTag("fetching_live"),
                    color = p.accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = p.ink)
            }
        }
    }
}

/** Origin and destination, each opening the picker, and a way to swap them */
@Composable
private fun PlacesPanel(
    from: TripPlace?,
    to: TripPlace?,
    locating: Boolean,
    onLocate: () -> Unit,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    canSwap: Boolean,
    onSwap: () -> Unit,
) {
    val p = LabTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PlaceField(
                text = from?.name,
                placeholder = stringResource(R.string.choose_origin),
                onClick = onFrom,
                marker = { OriginDot(p) },
                modifier = Modifier.testTag("origin_field"),
                trailing = { LocateButton(locating, onLocate) },
            )
            Spacer(Modifier.height(8.dp))
            PlaceField(
                text = to?.name,
                placeholder = stringResource(R.string.choose_destination),
                onClick = onTo,
                marker = { DestinationSquare(p) },
                modifier = Modifier.testTag("destination_field"),
            )
        }
        Spacer(Modifier.width(8.dp))
        LabIconButton(
            icon = painterResource(R.drawable.ic_swap_vert),
            contentDescription = stringResource(R.string.swap_places),
            onClick = onSwap,
            enabled = canSwap,
            size = 44.dp,
        )
    }
}

@Composable
private fun PlaceField(
    text: String?,
    placeholder: String,
    onClick: () -> Unit,
    marker: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LabTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .background(p.paper)
            .border(1.dp, p.rule)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { marker() }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text ?: placeholder,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge.copy(color = if (text == null) p.muted else p.ink),
        )
        trailing?.invoke()
    }
}

/** Start from where the device is now: a fresh fix, on demand */
@Composable
private fun LocateButton(locating: Boolean, onClick: () -> Unit) {
    val p = LabTheme.palette
    val description = stringResource(R.string.update_location)
    LabTooltip(description) {
        Box(
            Modifier
                .size(width = 32.dp, height = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 20.dp),
                    enabled = !locating,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (locating) {
                CircularProgressIndicator(
                    Modifier
                        .size(16.dp)
                        .testTag("locating"),
                    color = p.accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_my_location),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = p.muted,
                )
            }
        }
    }
}

/**
 * The option open, on a map of its own framing the whole trip: walks
 * dotted, rides in accent, the stops where a bus is boarded or left, and
 * both places. North up: a trip reads that way, with no compass to bring
 * it back.
 */
@Composable
private fun TripMap(
    viewModel: PlanTripViewModel,
    option: TripOption,
    from: TripPlace?,
    to: TripPlace?,
    tiles: MapTileSource,
    darkTiles: Boolean,
    carto: Boolean,
    creditsOpen: Boolean,
    onCredits: (Boolean) -> Unit,
    fadeIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = tilesPalette(dark = darkTiles)
    // A long press on a stop, by marker id, counted so each one shows its name again
    var held by remember { mutableStateOf<Pair<String, Int>?>(null) }

    Box(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val map = viewModel.mapFor(DpSize(maxWidth, maxHeight)) ?: return@BoxWithConstraints

            // Moving the map puts the credits away, as tapping elsewhere would
            LaunchedEffect(map) {
                snapshotFlow { map.camera.center to map.camera.zoom }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { onCredits(false) }
            }

            val stops = remember(option) {
                option.rides.flatMapIndexed { r, ride -> listOf("$BOARD$r" to ride.board, "$ALIGHT$r" to ride.alight) }.toMap()
            }
            val markers = remember(stops, from, to) {
                buildList {
                    for ((id, stop) in stops) add(LabMapMarker(id, GeoPoint(stop.latitude, stop.longitude), zIndex = 0f))
                    from?.let { add(LabMapMarker(FROM, GeoPoint(it.latitude, it.longitude), zIndex = 1f)) }
                    to?.let { add(LabMapMarker(TO, GeoPoint(it.latitude, it.longitude), zIndex = 2f)) }
                }
            }
            LabMap(
                state = map,
                tiles = tiles,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("map"),
                markers = markers,
                markerContent = { id ->
                    when (id) {
                        FROM -> Box(Modifier.size(24.dp).testTag("map_from"), contentAlignment = Alignment.Center) {
                            OriginDot(palette)
                        }
                        TO -> Box(Modifier.size(24.dp).testTag("map_to"), contentAlignment = Alignment.Center) {
                            DestinationSquare(palette)
                        }
                        else -> stops[id]?.let { stop ->
                            StopSquare(
                                name = stop.name,
                                tiles = palette,
                                held = held?.takeIf { it.first == id }?.second ?: 0,
                                modifier = Modifier.testTag("map_stop_${stop.stopId}"),
                            )
                        }
                    }
                },
                onMarkerLongPress = { id -> if (id in stops) held = id to (held?.second ?: 0) + 1 },
                polylines = remember(option, palette) { legLines(option, palette) },
                fadeIn = fadeIn,
            )
        }

        MapCredits(
            open = creditsOpen,
            onToggle = { onCredits(!creditsOpen) },
            tiles = palette,
            carto = carto,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp),
        )
    }
}

/** Each leg as a line: walks dotted in ink, rides in accent on a paper border */
private fun legLines(option: TripOption, tiles: LabPalette): List<LabMapPolyline> =
    option.legs.mapIndexed { i, leg ->
        when (leg) {
            is WalkLeg -> LabMapPolyline(
                id = "walk:$i",
                points = listOf(GeoPoint(leg.fromLatitude, leg.fromLongitude), GeoPoint(leg.toLatitude, leg.toLongitude)),
                color = tiles.ink,
                width = 3.dp,
                dotted = true,
            )
            is RideLeg -> LabMapPolyline(
                id = "ride:$i",
                points = leg.stops.map { GeoPoint(it.latitude, it.longitude) },
                color = tiles.accent,
                width = 5.dp,
                borderColor = tiles.paper,
                borderWidth = 1.5.dp,
            )
        }
    }

private const val BOARD = "board:"
private const val ALIGHT = "alight:"
private const val FROM = "from"
private const val TO = "to"

/** What the area below the places shows */
@Composable
private fun Results(
    results: PlanResults,
    list: LazyListState,
    now: () -> Long,
    onRetry: () -> Unit,
    onToggle: (row: OptionRow, listIndex: Int) -> Unit,
    onChooseLine: (plan: Int, ride: Int, line: String) -> Unit,
) {
    val p = LabTheme.palette
    when (results) {
        PlanResults.NetworkFailed -> LabEmptyState(
            icon = painterResource(R.drawable.ic_cloud_off),
            message = stringResource(R.string.could_not_load_arrivals),
            actionLabel = stringResource(R.string.retry),
            onAction = onRetry,
            secondaryAction = true,
            modifier = Modifier.testTag("plan_message"),
        )
        is PlanResults.LoadingRoutes -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(32.dp)
                    .testTag("loading_routes"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.loading_routes, results.done, results.total),
                    textAlign = TextAlign.Center,
                    style = LabText.statusLine(p),
                )
                Spacer(Modifier.height(12.dp))
                val bar = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                if (results.total > 0) {
                    LinearProgressIndicator(
                        progress = { results.done.toFloat() / results.total },
                        modifier = bar,
                        color = p.accent,
                        trackColor = p.rule,
                        strokeCap = StrokeCap.Butt,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(bar, color = p.accent, trackColor = p.rule, strokeCap = StrokeCap.Butt, gapSize = 0.dp)
                }
            }
        }
        PlanResults.Planning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                Modifier
                    .size(36.dp)
                    .testTag("planning"),
                color = p.accent,
                strokeWidth = 4.dp,
            )
        }
        PlanResults.Hint -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.plan_hint),
                textAlign = TextAlign.Center,
                style = LabText.statusLine(p),
                modifier = Modifier
                    .padding(24.dp)
                    .testTag("plan_hint"),
            )
        }
        PlanResults.NoTrip -> LabEmptyState(
            icon = painterResource(R.drawable.ic_route),
            message = stringResource(R.string.no_trip_found),
            modifier = Modifier.testTag("plan_message"),
        )
        is PlanResults.Options -> Options(results, list, now, onToggle, onChooseLine)
    }
}

@Composable
private fun Options(
    results: PlanResults.Options,
    list: LazyListState,
    now: () -> Long,
    onToggle: (row: OptionRow, listIndex: Int) -> Unit,
    onChooseLine: (plan: Int, ride: Int, line: String) -> Unit,
) {
    val p = LabTheme.palette
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    // Rows before the options, to bring an opened one to the top
    val before = (if (results.replanning) 1 else 0) + (if (results.liveFailed) 1 else 0)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("options"),
        state = list,
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        if (results.replanning) {
            item(key = "replanning") {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .testTag("replanning"),
                    color = p.accent,
                    trackColor = p.ruleSoft,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                )
            }
        }
        if (results.liveFailed) {
            item(key = "live_failed") {
                Row(
                    Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(p.warn),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.live_unavailable),
                        modifier = Modifier.weight(1f),
                        style = LabText.statusLine(p),
                    )
                }
            }
        }
        itemsIndexed(results.rows, key = { _, row -> row.index }) { i, row ->
            if (i > 0) LabDivider()
            OptionTile(
                row = row,
                now = now,
                onClick = { onToggle(row, before + i) },
                onChooseLine = { ride, line -> onChooseLine(row.index, ride, line) },
            )
        }
        item(key = "note") {
            Text(
                stringResource(R.string.trip_estimate_note),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                style = LabText.statusLine(p),
            )
        }
    }
}

/**
 * One way to get there: time and the legs at a glance, and each step once
 * opened. Only the summary opens and closes it: the steps below hold
 * buttons, and a tap beside one should not fold them away.
 */
@Composable
private fun OptionTile(row: OptionRow, now: () -> Long, onClick: () -> Unit, onChooseLine: (ride: Int, line: String) -> Unit) {
    val p = LabTheme.palette
    val option = row.option
    val expanded = row.expanded
    val arrival = clock(option.arrivesAt ?: (now() + option.duration.inWholeMilliseconds))
    val firstRide = option.firstRide
    val facts = listOfNotNull(
        firstRide?.let { transfers(option.transfers) },
        stringResource(R.string.walk_distance, roundTo10(option.walkMeters)),
    ).joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (expanded) p.paper2 else Color.Transparent)
            .drawBehind {
                if (expanded) drawRect(p.accent, size = size.copy(width = 3.dp.toPx()))
            }
            .padding(start = 3.dp)
            .semantics { selected = expanded }
            .testTag("option_${row.index}"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 13.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.minutes_short, minutes(option.duration)),
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.titleLarge.copy(color = p.ink),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(if (option.isLive) R.string.arrive_at else R.string.arrive_around, arrival),
                    modifier = Modifier.alignByBaseline(),
                    style = LabText.statusLine(p),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.CenterVertically),
                    tint = p.muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            LegsStrip(option)
            Spacer(Modifier.height(8.dp))
            if (firstRide != null) FirstBus(firstRide, now)
            Text(facts, style = LabText.statusLine(p))
        }
        if (expanded) {
            Box(Modifier.padding(start = 13.dp, end = 16.dp, bottom = 12.dp)) {
                Steps(option, onChooseLine)
            }
        }
    }
}

/** "Direct", "1 transfer" or more, as the Flutter string's own words for none */
@Composable
private fun transfers(count: Int): String =
    if (count == 0) stringResource(R.string.transfers_none) else pluralStringResource(R.plurals.transfers_count, count, count)

/** Walks shorter than this are a step across a station, not a leg */
private const val MIN_WALK_METERS = 60.0

/** The legs in a row: minutes on foot, then the line chips */
@Composable
private fun LegsStrip(option: TripOption) {
    val p = LabTheme.palette
    val shown = option.legs.filter { it is RideLeg || (it is WalkLeg && (it.meters >= MIN_WALK_METERS || option.rides.isEmpty())) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { i, leg ->
            if (i > 0) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = p.muted,
                )
            }
            when (leg) {
                is WalkLeg -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_directions_walk),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = p.muted,
                    )
                    Text("${minutes(leg.duration)}", style = LabText.mono(p, size = 13f, color = p.muted))
                }
                is RideLeg -> LabLineChip(leg.lines.joinToString(" / "))
            }
        }
    }
}

/** When the first bus leaves: a live one's time, or the usual wait when none is on its way */
@Composable
private fun FirstBus(ride: RideLeg, now: () -> Long) {
    val p = LabTheme.palette
    val departsAt = ride.departsAt
    val text = if (ride.liveWait && departsAt != null) {
        // Whole seconds, as Dart's inSeconds, then rounded to minutes
        val minutes = (((departsAt - now()) / 1000) / 60.0).roundToInt()
        if (minutes <= 0) {
            stringResource(R.string.next_bus_now, ride.line)
        } else {
            stringResource(R.string.bus_leaves_at, ride.line, clock(departsAt), minutes)
        }
    } else {
        stringResource(R.string.no_live_bus, minutes(ride.wait))
    }
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .testTag("first_bus"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (ride.liveWait) p.ok else p.warn),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$text · ${ride.board.name}",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LabText.mono(p, size = 12f, color = p.ink),
        )
    }
}

/** Each step of the chosen option, in words */
@Composable
private fun Steps(option: TripOption, onChooseLine: (ride: Int, line: String) -> Unit) {
    val p = LabTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(p.rule, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(top = 8.dp)
            .testTag("steps"),
    ) {
        for (leg in option.legs) {
            when (leg) {
                is WalkLeg -> {
                    val stop = leg.toStop
                    Step(
                        icon = R.drawable.ic_directions_walk,
                        text = if (stop == null) {
                            stringResource(R.string.step_walk_to_destination, minutes(leg.duration))
                        } else {
                            stringResource(R.string.step_walk_to, minutes(leg.duration), stop.name)
                        },
                        detail = stringResource(R.string.walk_distance, roundTo10(leg.meters)),
                    )
                }
                is RideLeg -> {
                    val departsAt = leg.departsAt
                    Step(
                        icon = R.drawable.ic_directions_bus_rounded,
                        text = stringResource(R.string.step_ride, leg.line, leg.towards),
                        detail = listOf(
                            if (leg.liveWait && departsAt != null) {
                                stringResource(R.string.leaves_at, clock(departsAt))
                            } else {
                                stringResource(R.string.wait_about, minutes(leg.wait))
                            },
                            pluralStringResource(R.plurals.stops_count, leg.stopCount, leg.stopCount),
                            (if (leg.liveRide) "" else "~") + stringResource(R.string.minutes_short, minutes(leg.duration)),
                        ).joinToString(" · "),
                        tone = p.accent,
                        below = if (leg.lines.size < 2) {
                            null
                        } else {
                            { LineChoice(leg, onChoose = { line -> onChooseLine(option.rides.indexOf(leg), line) }) }
                        },
                    )
                    Step(icon = R.drawable.ic_logout, text = stringResource(R.string.step_get_off, leg.alight.name), tone = p.accent)
                }
            }
        }
    }
}

@Composable
private fun Step(
    icon: Int,
    text: String,
    detail: String? = null,
    tone: Color? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val p = LabTheme.palette
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = tone ?: p.muted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = p.ink))
            if (detail != null) Text(detail, style = LabText.statusLine(p))
            if (below != null) {
                Spacer(Modifier.height(8.dp))
                below()
            }
        }
    }
}

/**
 * The lines that make one ride, as buttons: the one shown is lit, and
 * tapping another draws its own route and platforms
 */
@Composable
private fun LineChoice(ride: RideLeg, onChoose: (String) -> Unit) {
    val p = LabTheme.palette
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (line in ride.lines) {
                val chosen = line == ride.line
                Box(
                    Modifier
                        .widthIn(min = 48.dp)
                        .background(if (chosen) p.accent else Color.Transparent)
                        .border(1.dp, p.accent)
                        .clickable(enabled = !chosen, role = Role.Button) { onChoose(line) }
                        .semantics { selected = chosen }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("line_choice_$line"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        line,
                        textAlign = TextAlign.Center,
                        style = LabText.mono(p, size = 13f, color = if (chosen) p.paper else p.accent),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.line_choice_hint), style = LabText.statusLine(p))
    }
}

/** Where the trip starts: a round dot, unlike the square stops */
@Composable
private fun OriginDot(tiles: LabPalette) {
    Box(
        Modifier
            .size(16.dp)
            .background(tiles.paper, CircleShape)
            .padding(2.5.dp)
            .background(tiles.ink, CircleShape),
    )
}

/** Where the trip ends: a filled red square */
@Composable
private fun DestinationSquare(tiles: LabPalette) {
    Box(
        Modifier
            .size(16.dp)
            .background(tiles.crit)
            .border(2.dp, tiles.paper),
    )
}

/**
 * A stop where a bus is boarded or left, named by a tooltip each time
 * [held] grows, as a long press on a Flutter tooltip does. The map takes
 * the press, so the tooltip takes no input of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopSquare(name: String, tiles: LabPalette, held: Int, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    val tooltip = rememberTooltipState()
    LaunchedEffect(held) { if (held > 0) tooltip.show() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip(shape = RectangleShape, containerColor = p.ink, contentColor = p.bg) {
                Text(name, style = MaterialTheme.typography.bodySmall.copy(color = p.bg))
            }
        },
        state = tooltip,
        enableUserInput = false,
    ) {
        Box(
            modifier
                .size(20.dp)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(tiles.paper)
                    .border(2.dp, tiles.ink),
            )
        }
    }
}

/** Whole minutes, never zero: a step always takes some time */
internal fun minutes(duration: Duration): Int = max(1, (duration.inWholeSeconds / 60.0).roundToInt())

internal fun roundTo10(meters: Double): Int = (meters / 10).roundToInt() * 10

/** A time of day as the device writes it, as Flutter's TimeOfDay.format */
@Composable
private fun clock(epochMillis: Long): String {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    return clockText(epochMillis, hours24 = DateFormat.is24HourFormat(context), spanish = language == "es")
}

/**
 * [epochMillis] as Flutter's TimeOfDay.format writes it with the Material
 * localizations of the app's two languages: "7:05" in Spanish, 24 hours
 * or not; in English "7:05 AM", or "07:05" when the phone keeps 24 hours
 */
internal fun clockText(epochMillis: Long, hours24: Boolean, spanish: Boolean): String {
    val pattern = when {
        spanish -> "H:mm"
        hours24 -> "HH:mm"
        else -> "h:mm a"
    }
    return SimpleDateFormat(pattern, Locale.ENGLISH).format(Date(epochMillis))
}
