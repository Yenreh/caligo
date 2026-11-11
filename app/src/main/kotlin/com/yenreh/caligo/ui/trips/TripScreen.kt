package com.yenreh.caligo.ui.trips

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyLeg
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.TripSource
import com.yenreh.caligo.trips.AwaitedLine
import com.yenreh.caligo.trips.EndedTrip
import com.yenreh.caligo.ui.common.LiveClock
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.LocalNow
import com.yenreh.caligo.ui.common.watchLiveClock
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabOutlinedButton
import com.yenreh.caligo.ui.lab.LabPanel
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.map.HaloIcon
import com.yenreh.caligo.ui.map.LabMap
import com.yenreh.caligo.ui.map.LabMapMarker
import com.yenreh.caligo.ui.map.LabMapPolyline
import com.yenreh.caligo.ui.map.LabMapState
import com.yenreh.caligo.ui.map.MapButton
import com.yenreh.caligo.ui.map.MapCredits
import com.yenreh.caligo.ui.map.MapTileSource
import com.yenreh.caligo.ui.map.tilesPalette
import com.yenreh.caligo.ui.theme.LabPalette
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme

/** What the trip screen shows */
sealed interface TripContent {
    data object Loading : TripContent

    data object Failed : TripContent

    /** The line's routes do not call at the boarding stop */
    data class NotOnRoute(val line: String) : TripContent

    /** "Where do you get off?" */
    data class Choosing(val plan: TripPlan, val starting: Boolean = false) : TripContent

    data class Running(val journey: Journey) : TripContent

    /** The trip ended, as [ended] says; null with nothing to tell */
    data class Ended(val ended: EndedTrip?) : TripContent
}

/** The tiles of the trip map, as the settings ask */
data class TripMapTiles(val tiles: MapTileSource, val dark: Boolean, val fadeIn: Boolean = true)

/** The trip screen with its view model; [onBack] closes it */
@Composable
fun TripRoute(viewModel: TripViewModel, onBack: () -> Unit, tiles: TripMapTiles? = null) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val running by viewModel.trips.running.collectAsStateWithLifecycle()
    val ended by viewModel.trips.ended.collectAsStateWithLifecycle()
    val awaited by viewModel.trips.awaited.collectAsStateWithLifecycle()
    val took = viewModel.took
    val content = when {
        !screen.started -> when (val plan = screen.plan) {
            Loaded.Loading -> TripContent.Loading
            is Loaded.Failed -> TripContent.Failed
            is Loaded.Ready -> plan.value?.let { TripContent.Choosing(it, screen.starting) }
                ?: TripContent.NotOnRoute(took?.line.orEmpty())
        }
        running != null -> TripContent.Running(running!!)
        else -> TripContent.Ended(ended)
    }
    val shown = running ?: ended?.journey
    val openSettings = rememberTripMessageSettings(viewModel::openNotificationSettings)
    // Restored long after its ride: nothing to show
    LaunchedEffect(screen.stale) { if (screen.stale) onBack() }
    TripScreen(
        content = content,
        title = shown?.let { journeyTitle(it) }
            ?: took?.let { stringResource(R.string.trip_title, it.line, it.destination) }
            ?: stringResource(R.string.trip_channel),
        subtitle = (shown?.takeIf { !it.planned }?.ride?.plan?.took ?: took?.takeIf { shown == null })
            ?.let { stringResource(R.string.trip_from, it.stopName) },
        tiles = tiles,
        onBack = onBack,
        onRetry = viewModel::retry,
        onChoose = viewModel::choose,
        onAlight = viewModel::alightAt,
        awaited = awaited,
        onTookLine = viewModel::takeLine,
        onFinish = {
            viewModel.finish()
            onBack()
        },
        message = screen.message,
        onMessageSettings = { message ->
            viewModel.dismissMessage()
            openSettings(message)
        },
        onMessageDismiss = viewModel::dismissMessage,
    )
}

/** A planner trip by where it goes, a single ride by its line */
@Composable
private fun journeyTitle(journey: Journey): String {
    if (journey.planned) return stringResource(R.string.trip_to_destination, journey.destinationName)
    val took = journey.ride?.plan?.took ?: return stringResource(R.string.trip_channel)
    return stringResource(R.string.trip_title, took.line, took.destination)
}

/** Opens the settings that fix what [TripMessage] says is missing */
@Composable
fun rememberTripMessageSettings(openNotificationSettings: () -> Unit): (TripMessage) -> Unit {
    val context = LocalContext.current
    val notifications by rememberUpdatedState(openNotificationSettings)
    return remember(context) { { message -> openTripSettings(context, message, notifications) } }
}

private fun openTripSettings(context: Context, message: TripMessage, notifications: () -> Unit) {
    when (message) {
        TripMessage.NEEDS_NOTIFICATIONS -> notifications()
        TripMessage.NEEDS_LOCATION -> context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        TripMessage.LOCATION_OFF -> context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * The trip screen: where to get off, then the trip as it goes: the map
 * over the leg on now, the rider, their bus and the stops that matter,
 * the legs still to come drawn quieter; below it, the leg in words, the
 * ride's stops ahead with their times and the one to get off at, which a
 * tap on another moves, what comes after, and Finish.
 */
@Composable
fun TripScreen(
    content: TripContent,
    title: String,
    modifier: Modifier = Modifier,
    /** Where the ride was boarded: "From Plaza de Cayzedo A1" */
    subtitle: String? = null,
    /** The map's tiles; none draws no map */
    tiles: TripMapTiles? = null,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onChoose: (Int?) -> Unit = {},
    onAlight: (Int?) -> Unit = {},
    /** The buses a planner trip awaits for the ride ahead, soonest first */
    awaited: List<AwaitedLine> = emptyList(),
    /** "I took the A47", while waiting at the stop */
    onTookLine: (String) -> Unit = {},
    onFinish: () -> Unit = {},
    message: TripMessage? = null,
    onMessageSettings: (TripMessage) -> Unit = {},
    onMessageDismiss: () -> Unit = {},
) {
    val p = LabTheme.palette
    LiveClock {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = p.bg,
            contentWindowInsets = WindowInsets(0),
            // The boarding stop goes in the content, where large text has room for it
            topBar = { LabTopBar(title = title, onBack = onBack) },
            bottomBar = {
                when (content) {
                    is TripContent.Choosing -> BottomButton {
                        LabOutlinedButton(
                            onClick = { onChoose(null) },
                            enabled = !content.starting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trip_skip"),
                        ) { Text(stringResource(R.string.trip_skip)) }
                    }
                    is TripContent.Running -> BottomButton {
                        LabFilledButton(
                            onClick = onFinish,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trip_finish"),
                        ) { Text(stringResource(R.string.trip_finish)) }
                    }
                    else -> {}
                }
            },
        ) { padding ->
            val body = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
            when (content) {
                TripContent.Loading -> Column(body, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = p.accent)
                }
                TripContent.Failed -> LabEmptyState(
                    icon = painterResource(R.drawable.ic_cloud_off),
                    message = stringResource(R.string.could_not_load_route),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                    modifier = body,
                )
                is TripContent.NotOnRoute -> LabEmptyState(
                    icon = painterResource(R.drawable.ic_route),
                    message = stringResource(R.string.trip_not_on_route, content.line),
                    modifier = body,
                )
                is TripContent.Choosing -> Choosing(content.plan, subtitle, onChoose, body, enabled = !content.starting)
                is TripContent.Running -> Column(body) {
                    if (tiles != null) {
                        TripMap(
                            content.journey,
                            tiles,
                            Modifier
                                .fillMaxWidth()
                                .height(mapHeight()),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(p.ink),
                        )
                    }
                    Running(content.journey, subtitle, onAlight, awaited, onTookLine, Modifier.weight(1f))
                }
                is TripContent.Ended -> LabEmptyState(
                    icon = painterResource(R.drawable.ic_directions_bus),
                    message = endedText(content.ended),
                    actionLabel = stringResource(R.string.wait_close),
                    onAction = onBack,
                    secondaryAction = true,
                    modifier = body.testTag("trip_ended"),
                )
            }
        }
    }
    message?.let { TripMessageDialog(it, onSettings = { onMessageSettings(it) }, onDismiss = onMessageDismiss) }
}

/** The map's share of the window: enough to see the stretch ahead, the words below it at any text size */
@Composable
private fun mapHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * MAP_SHARE

private const val MAP_SHARE = 0.36f

@Composable
private fun BottomButton(content: @Composable () -> Unit) {
    val p = LabTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { content() }
}

/** "Where do you get off?" over the stops ahead of the boarding one, each a tap to choose */
@Composable
private fun Choosing(plan: TripPlan, from: String?, onChoose: (Int?) -> Unit, modifier: Modifier, enabled: Boolean) {
    val now = LocalNow.current
    watchLiveClock()
    val at = now()
    val estimate = Trip.start(plan, at)
    LazyColumn(modifier.testTag("trip_choose")) {
        item {
            Text(
                stringResource(R.string.trip_where_off),
                style = MaterialTheme.typography.headlineSmall.copy(color = LabTheme.palette.ink),
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (from != null) 4.dp else 16.dp)
                    .semantics { heading() },
            )
        }
        if (from != null) {
            item {
                Text(
                    from,
                    style = MaterialTheme.typography.bodyMedium.copy(color = LabTheme.palette.muted),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        items(plan.ahead.toList(), key = { "stop:$it" }) { index ->
            val stop = plan.stops[index]
            val minutes = estimate.minutes(estimate.millisTo(index, at))
            LabListTile(
                title = stop.name,
                subtitle = stringResource(R.string.trip_minutes, "~$minutes"),
                modifier = Modifier
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.trip_get_off_at_action, stop.name)) {
                        onChoose(index)
                    }
                    .testTag("trip_choose_${stop.stopId}"),
            )
        }
    }
}

/** The trip as it goes: the leg on now in words, the ride's stops ahead with their times, and what comes after */
@Composable
private fun Running(
    journey: Journey,
    from: String?,
    onAlight: (Int?) -> Unit,
    awaited: List<AwaitedLine>,
    onTookLine: (String) -> Unit,
    modifier: Modifier,
) {
    val p = LabTheme.palette
    val now = LocalNow.current
    watchLiveClock()
    val at = now()
    val phase = journey.phase(at)
    LazyColumn(modifier.testTag("trip_running")) {
        item {
            LabPanel(modifier = Modifier.padding(16.dp), accentTop = true) {
                when (phase) {
                    is JourneyPhase.Riding -> RideHeading(phase.trip, at)
                    is JourneyPhase.Walking -> {
                        val minutes = Trip.minutesOf(phase.millis)
                        Heading(stringResource(R.string.trip_heading_walk, phase.leg.toName))
                        Detail(stringResource(R.string.trip_walk_detail, (if (phase.estimated) "~" else "") + minutes, roundTo10(phase.meters)))
                        Source(if (phase.estimated) R.string.trip_by_estimate else R.string.trip_by_fix)
                        if (awaited.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.trip_then, TripLines.of(awaited.map { lineIn(it, at) }, orPattern())),
                                style = MaterialTheme.typography.bodyMedium.copy(color = p.ink),
                            )
                        }
                    }
                    is JourneyPhase.Waiting -> {
                        // The soonest first, then the lines with no bus awaited
                        val ordered = (awaited.map { it.line } + phase.leg.ride.lines).distinct()
                        Heading(stringResource(R.string.trip_heading_take, TripLines.of(ordered, orPattern())))
                        Detail(stringResource(R.string.trip_at_stop, phase.leg.ride.board.name))
                        if (awaited.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                awaited.map { lineMinutes(it, at) }.joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                                modifier = Modifier.testTag("trip_awaited"),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.trip_took_it_hint), style = LabText.statusLine(p))
                        for (line in ordered) {
                            Spacer(Modifier.height(8.dp))
                            LabOutlinedButton(
                                onClick = { onTookLine(line) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("trip_took_$line"),
                            ) { Text(stringResource(R.string.trip_took_line, line)) }
                        }
                    }
                }
                if (from != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(from, style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                }
            }
        }
        if (phase is JourneyPhase.Riding) rideStops(phase.trip, at, onAlight)
        val ahead = journey.ahead
        if (ahead.isNotEmpty()) {
            item {
                LabSectionLabel(
                    stringResource(R.string.trip_then_label),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(ahead.size, key = { "ahead:$it" }) { i -> AheadRow(ahead[i]) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(color = LabTheme.palette.ink),
        modifier = Modifier
            .semantics { heading() }
            .testTag("trip_heading"),
    )
}

@Composable
private fun Detail(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(color = LabTheme.palette.ink, fontWeight = FontWeight.Medium),
        modifier = Modifier.testTag("trip_detail"),
    )
}

@Composable
private fun Source(text: Int) {
    Spacer(Modifier.height(4.dp))
    Text(stringResource(text), style = LabText.statusLine(LabTheme.palette))
}

@Composable
private fun RideHeading(trip: Trip, at: Long) {
    val progress = trip.progress(at)
    val plan = trip.plan
    val estimated = progress.source == TripSource.ESTIMATE
    val target = plan.stops[plan.target]
    Heading(
        when {
            plan.alight != null && progress.stopsLeft == 0 -> stringResource(R.string.trip_heading_here)
            plan.alight != null -> stringResource(R.string.trip_heading_get_off, target.name)
            else -> stringResource(R.string.trip_heading_follow, target.name)
        },
    )
    if (progress.stopsLeft > 0) {
        val minutes = trip.minutes(trip.millisTo(plan.target, at))
        Detail(
            pluralStringResource(
                R.plurals.trip_heading_detail,
                progress.stopsLeft,
                (if (estimated) "~" else "") + minutes,
                progress.stopsLeft,
            ),
        )
    }
    Source(
        when (progress.source) {
            TripSource.FIX -> R.string.trip_by_fix
            TripSource.BUS -> R.string.trip_by_bus
            TripSource.ESTIMATE -> R.string.trip_by_estimate
        },
    )
}

/** The ride's stops ahead, each with its time, the one to get off at marked and the others a tap to choose */
private fun androidx.compose.foundation.lazy.LazyListScope.rideStops(trip: Trip, at: Long, onAlight: (Int?) -> Unit) {
    val plan = trip.plan
    val progress = trip.progress(at)
    val estimated = progress.source == TripSource.ESTIMATE
    item {
        LabSectionLabel(
            stringResource(R.string.trip_stops_ahead),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
    val ahead = ((progress.stop + 1).coerceAtMost(plan.stops.lastIndex)..plan.stops.lastIndex).toList()
    items(ahead, key = { "stop:$it" }) { index ->
        val p = LabTheme.palette
        val stop = plan.stops[index]
        val alighting = index == plan.alight
        val minutes = trip.minutes(trip.millisTo(index, at))
        val time = stringResource(R.string.trip_minutes, (if (estimated) "~" else "") + minutes)
        LabListTile(
            title = stop.name,
            subtitle = if (alighting) "$time · ${stringResource(R.string.trip_you_get_off)}" else time,
            contentColor = if (alighting) p.accent else null,
            titleWeight = if (alighting) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier
                .clickable(
                    enabled = !alighting,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.trip_get_off_at_action, stop.name),
                ) { onAlight(index) }
                .heightIn(min = 48.dp)
                .testTag(if (alighting) "trip_alight" else "trip_stop_${stop.stopId}"),
        )
    }
}

@Composable
private fun orPattern(): String = stringResource(R.string.trip_lines_or, "%1\$s", "%2\$s")

@Composable
private fun lineIn(bus: AwaitedLine, now: Long): String = if (bus.atStop) {
    stringResource(R.string.trip_line_at_stop, bus.line)
} else {
    stringResource(R.string.trip_line_in, bus.line, Trip.minutesOf(bus.dueAt(now) - now))
}

@Composable
private fun lineMinutes(bus: AwaitedLine, now: Long): String = if (bus.atStop) {
    stringResource(R.string.trip_line_at_stop, bus.line)
} else {
    stringResource(R.string.trip_line_minutes, bus.line, Trip.minutesOf(bus.dueAt(now) - now))
}

/** A leg still to come, in muted words */
@Composable
private fun AheadRow(leg: JourneyLeg) {
    val p = LabTheme.palette
    val text = when (leg) {
        is JourneyLeg.Walk -> stringResource(R.string.trip_ahead_walk, leg.toName, minutes(leg.walk.duration))
        is JourneyLeg.Ride -> pluralStringResource(
            R.plurals.trip_ahead_ride,
            leg.ride.stopCount,
            TripLines.of(leg.ride.lines, stringResource(R.string.trip_lines_or, "%1\$s", "%2\$s")),
            leg.ride.alight.name,
            leg.ride.stopCount,
        )
    }
    LabListTile(
        title = text,
        leading = painterResource(if (leg is JourneyLeg.Walk) R.drawable.ic_directions_walk else R.drawable.ic_directions_bus_rounded),
        titleWeight = FontWeight.Normal,
        contentColor = p.muted,
    )
}

/** The lines of a ride in words: "E21", "E21 or T31", "E21, T31 or A47", by the language's [pattern] for the last two */
internal object TripLines {
    fun of(lines: List<String>, pattern: String): String = when (lines.size) {
        0 -> ""
        1 -> lines.single()
        else -> pattern.replace("%1\$s", lines.dropLast(1).joinToString(", ")).replace("%2\$s", lines.last())
    }
}

/**
 * The trip on the map: its model drawn, framed on the leg on now as it
 * opens, then following the rider until the user moves the map, with a
 * button to follow them again
 */
@Composable
private fun TripMap(journey: Journey, tiles: TripMapTiles, modifier: Modifier) {
    val now = LocalNow.current
    watchLiveClock()
    val model = TripMapModel.of(journey, now())
    val palette = tilesPalette(dark = tiles.dark)
    var map by remember { mutableStateOf<LabMapState?>(null) }
    var follow by remember { mutableStateOf(MapFollow()) }
    var creditsOpen by remember { mutableStateOf(false) }
    val rider by rememberUpdatedState(model.rider)

    Box(modifier.testTag("trip_map")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = DpSize(maxWidth, maxHeight)
            val state = map ?: model.frame.takeIf { it.isNotEmpty() }?.let {
                LabMapState.fitting(it, viewport, FIT_PADDING, MIN_ZOOM, LabMapState.MAX_ZOOM, rotatable = false, fitMaxZoom = FIT_MAX_ZOOM)
                    .also { created -> map = created }
            } ?: return@BoxWithConstraints

            // The map moved by the user stops following; the rider moving is followed
            LaunchedEffect(state) {
                snapshotFlow { state.camera to rider }.collect { (camera, at) ->
                    val (next, moveTo) = follow.next(camera, at)
                    follow = next
                    moveTo?.let { state.move(it) }
                }
            }

            LabMap(
                state = state,
                tiles = tiles.tiles,
                modifier = Modifier.fillMaxSize(),
                markers = model.marks.map { LabMapMarker(it.id, it.point, zIndex = it.role.ordinal.let { r -> (TripMarkRole.entries.size - r).toFloat() }) },
                markerContent = { id -> model.marks.firstOrNull { it.id == id }?.let { TripMark(it.role, palette) } },
                polylines = model.lines.map { line(it, palette) },
                fadeIn = tiles.fadeIn,
            )
        }

        if (!follow.following) {
            MapButton(
                onClick = { follow = follow.recenter() },
                tooltip = stringResource(R.string.trip_follow_me),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .testTag("trip_follow"),
            ) { HaloIcon(painterResource(R.drawable.ic_my_location), tint = palette.ink, halo = palette.paper, size = 24.dp) }
        }
        MapCredits(
            open = creditsOpen,
            onToggle = { creditsOpen = !creditsOpen },
            tiles = palette,
            carto = tiles.tiles.byCarto,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp),
        )
    }
}

private fun line(line: TripMapLine, tiles: LabPalette): LabMapPolyline = when (line.role) {
    TripLineRole.RIDE -> LabMapPolyline(line.id, line.points, tiles.accent, width = 5.dp, borderColor = tiles.paper, borderWidth = 1.5.dp)
    TripLineRole.WALK -> LabMapPolyline(line.id, line.points, tiles.ink, width = 3.dp, dotted = true)
    TripLineRole.RIDE_AHEAD -> LabMapPolyline(line.id, line.points, tiles.muted, width = 4.dp, borderColor = tiles.paper, borderWidth = 1.dp)
    TripLineRole.WALK_AHEAD -> LabMapPolyline(line.id, line.points, tiles.muted, width = 3.dp, dotted = true)
}

@Composable
private fun TripMark(role: TripMarkRole, tiles: LabPalette) {
    when (role) {
        TripMarkRole.RIDER -> Box(
            Modifier
                .size(20.dp)
                .background(tiles.paper, CircleShape)
                .padding(3.dp)
                .background(tiles.accent, CircleShape)
                .testTag("trip_map_rider"),
        )
        TripMarkRole.BUS -> HaloIcon(
            painterResource(R.drawable.ic_directions_bus),
            tint = tiles.ink,
            halo = tiles.paper,
            size = 24.dp,
            modifier = Modifier.testTag("trip_map_bus"),
        )
        TripMarkRole.ALIGHT -> Box(
            Modifier
                .size(16.dp)
                .background(tiles.accent)
                .border(2.dp, tiles.paper)
                .testTag("trip_map_alight"),
        )
        TripMarkRole.BOARD -> Box(
            Modifier
                .size(12.dp)
                .background(tiles.paper)
                .border(2.dp, tiles.ink),
        )
        TripMarkRole.DESTINATION -> Box(
            Modifier
                .size(16.dp)
                .background(tiles.crit)
                .border(2.dp, tiles.paper),
        )
    }
}

private val FIT_PADDING = 40.dp
private const val MIN_ZOOM = 11.0
private const val FIT_MAX_ZOOM = 17.0

@Composable
private fun endedText(ended: EndedTrip?): String {
    // Never ran: the system would not start it
    if (ended?.refused == true) return stringResource(R.string.trip_ended_refused)
    return when (ended?.ending) {
        TripEnding.REACHED -> stringResource(R.string.trip_ended_reached, reachedName(ended.journey))
        TripEnding.DISMISSED -> stringResource(R.string.trip_ended_dismissed)
        TripEnding.TIME_LIMIT -> stringResource(R.string.trip_ended_time_limit)
        TripEnding.OFF_ROUTE -> stringResource(R.string.trip_ended_off_route)
        TripEnding.BLOCKED -> stringResource(R.string.trip_ended_blocked)
        TripEnding.NO_LOCATION -> stringResource(R.string.trip_ended_no_location)
        TripEnding.REPLACED -> stringResource(R.string.trip_ended_replaced)
        TripEnding.LEFT_STOP -> stringResource(R.string.trip_ended_left_stop)
        TripEnding.NO_BUS -> stringResource(R.string.trip_ended_no_bus, Journey.WAITING_LIMIT.inWholeMinutes.toInt())
        TripEnding.FINISHED -> stringResource(R.string.trip_ended_finished)
        // No trip ran in this process: nothing to tell of one
        null -> stringResource(R.string.trip_none)
    }
}

/** Where a trip that reached its end ended: its destination, or the stop got off at */
private fun reachedName(journey: Journey): String = journey.ride?.plan?.let { it.stops[it.target].name } ?: journey.destinationName

/** Why the trip did not start, with the way to the settings that fix it */
@Composable
fun TripMessageDialog(message: TripMessage, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val p = LabTheme.palette
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth()
                .background(p.paper)
                .border(1.dp, p.rule)
                .padding(24.dp)
                .testTag("trip_message"),
        ) {
            Text(
                text = stringResource(
                    when (message) {
                        TripMessage.NEEDS_NOTIFICATIONS -> R.string.wait_needs_notifications
                        TripMessage.NEEDS_LOCATION -> R.string.trip_needs_location
                        TripMessage.LOCATION_OFF -> R.string.trip_location_off
                    },
                ),
                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LabTextButton(onClick = onDismiss) { Text(stringResource(R.string.wait_close)) }
                Spacer(Modifier.width(8.dp))
                LabFilledButton(onClick = onSettings) { Text(stringResource(R.string.trip_open_settings)) }
            }
        }
    }
}
