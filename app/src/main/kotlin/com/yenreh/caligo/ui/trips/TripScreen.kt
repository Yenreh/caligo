package com.yenreh.caligo.ui.trips

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
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
import com.yenreh.caligo.trips.TripWords
import com.yenreh.caligo.trips.EndedTrip
import com.yenreh.caligo.trips.FinishingTrip
import com.yenreh.caligo.trips.LineTimes
import com.yenreh.caligo.trips.RideBuses
import com.yenreh.caligo.trips.TripArrival
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
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.lab.rememberUndoTime
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
    val rideBuses by viewModel.trips.rideBuses.collectAsStateWithLifecycle()
    val finishing by viewModel.trips.finishing.collectAsStateWithLifecycle()
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
        title = shown?.let { rememberTripWords().title(it) }
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
        onNotTaken = viewModel::notTaken,
        rideBuses = rideBuses,
        onChooseLine = viewModel::chooseLine,
        // The screen stays, the trip shown as finished, with Undo
        onFinish = viewModel::finish,
        finishing = finishing,
        onUndoFinish = viewModel::undoFinish,
        message = screen.message,
        onMessageSettings = { message ->
            viewModel.dismissMessage()
            openSettings(message)
        },
        onMessageDismiss = viewModel::dismissMessage,
    )
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
    /** "I took the A47", while waiting at the stop or near it on foot, or Yes as its bus left */
    onTookLine: (String) -> Unit = {},
    /** "No" as a bus of that line left */
    onNotTaken: (String) -> Unit = {},
    /** The buses at the stops of a planner trip's ride ahead, for its lines' rows */
    rideBuses: RideBuses? = null,
    /** Take the ride ahead on that line alone, or on any of its lines when null */
    onChooseLine: (String?) -> Unit = {},
    onFinish: () -> Unit = {},
    /** The trip just finished, while Undo can take it back */
    finishing: FinishingTrip? = null,
    onUndoFinish: () -> Unit = {},
    message: TripMessage? = null,
    onMessageSettings: (TripMessage) -> Unit = {},
    onMessageDismiss: () -> Unit = {},
) {
    val p = LabTheme.palette
    val snackbars = rememberLabSnackbars()
    FinishedSnackbar(finishing, snackbars, onUndoFinish)
    LiveClock {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = p.bg,
            contentWindowInsets = WindowInsets(0),
            // The boarding stop goes in the content, where large text has room for it; when it arrives, under the title
            topBar = { LabTopBar(title = title, onBack = onBack, subtitle = (content as? TripContent.Running)?.let { arrivalText(it.journey, awaited) }) },
            snackbarHost = { LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) },
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
                    // Outlined, as a mistaken tap has Undo
                    is TripContent.Running -> BottomButton {
                        LabOutlinedButton(
                            onClick = onFinish,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trip_finish"),
                        ) { Text(stringResource(R.string.trip_end)) }
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
                    Running(content.journey, subtitle, onAlight, snackbars, awaited, onTookLine, onNotTaken, rideBuses, onChooseLine, Modifier.weight(1f))
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

/**
 * "Trip finished" with Undo while [finishing] lasts: gone as it ends,
 * undone, run out or taken by another trip, never before, so its Undo
 * always works. It keeps to the service's window, not to the longer one
 * a screen reader may ask for.
 */
@Composable
private fun FinishedSnackbar(finishing: FinishingTrip?, snackbars: LabSnackbars, onUndo: () -> Unit) {
    val resources = LocalResources.current
    val now = LocalNow.current
    val undo by rememberUpdatedState(onUndo)
    // By the Finish, not by the trip, which moves on underneath: one Finish, one snackbar
    val started = finishing?.journey?.startedAt
    val until = finishing?.until
    LaunchedEffect(started, until, snackbars) {
        if (until == null) return@LaunchedEffect
        val left = (until - now()).coerceAtLeast(0).milliseconds
        val job = snackbars.showTimed(resources.getString(R.string.trip_finished_title), resources.getString(R.string.undo), left) { undo() }
        try {
            awaitCancellation()
        } finally {
            job.cancel()
        }
    }
}

/** "Arrive ~11:08": when the trip gets where it goes, as reckoned now; null with nowhere to get to */
@Composable
private fun arrivalText(journey: Journey, awaited: List<AwaitedLine>): String? {
    val now = LocalNow.current
    watchLiveClock()
    val arrival = TripArrival.of(journey, now(), awaited) ?: return null
    return stringResource(if (arrival.estimated) R.string.arrive_around else R.string.arrive_at, clock(arrival.at))
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
    val words = rememberTripWords()
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
            LabListTile(
                title = stop.name,
                subtitle = words.minutes(estimate.minutesTo(index, at), estimated = true),
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
    snackbars: LabSnackbars,
    awaited: List<AwaitedLine>,
    onTookLine: (String) -> Unit,
    onNotTaken: (String) -> Unit,
    rideBuses: RideBuses?,
    onChooseLine: (String?) -> Unit,
    modifier: Modifier,
) {
    val p = LabTheme.palette
    val now = LocalNow.current
    watchLiveClock()
    val at = now()
    val phase = journey.phase(at)
    val words = rememberTripWords()
    val change = rememberAlightChange(onAlight, snackbars)
    LazyColumn(modifier.testTag("trip_running")) {
        item {
            LabPanel(modifier = Modifier.padding(16.dp), accentTop = true) {
                when (phase) {
                    is JourneyPhase.Riding -> RideHeading(journey, phase.trip, at, words)
                    is JourneyPhase.Walking -> {
                        Heading(stringResource(R.string.trip_heading_walk, phase.leg.toName))
                        Detail(words.walkDetail(Trip.minutesOf(phase.millis), phase.estimated, roundTo10(phase.meters)))
                        Source(if (phase.estimated) estimateSource(journey) else R.string.trip_by_fix)
                        val next = journey.legs.getOrNull(journey.index + 1) as? JourneyLeg.Ride
                        if (phase.leg.toStop && next != null) {
                            // The soonest first, then the lines with no bus awaited, as at the stop
                            val lines = journey.pendingLines
                            val ordered = (awaited.map { it.line }.filter { it in lines } + lines).distinct()
                            Spacer(Modifier.height(4.dp))
                            Text(
                                words.thenTake(words.lines(ordered)),
                                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                                modifier = Modifier.testTag("trip_then"),
                            )
                            AwaitedTimes(awaited, at, words)
                            DidYouTake(awaited, lines, words, onTookLine, onNotTaken)
                            // Near the stop, as the notification's buttons show: the bus may come before the rider is there
                            if (journey.nearBoarding(at)) TookButtons(ordered.filter { it != askingLine(awaited, lines) }, onTookLine)
                        }
                    }
                    is JourneyPhase.Waiting -> {
                        // The soonest first, then the lines with no bus awaited
                        val lines = journey.pendingLines
                        val ordered = (awaited.map { it.line }.filter { it in lines } + lines).distinct()
                        Heading(words.take(words.lines(ordered)))
                        Detail(words.atStop(phase.leg.ride.board.name))
                        AwaitedTimes(awaited, at, words)
                        DidYouTake(awaited, lines, words, onTookLine, onNotTaken)
                        // Its Yes already says it of the line asked about
                        TookButtons(ordered.filter { it != askingLine(awaited, lines) }, onTookLine)
                    }
                }
                if (from != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(from, style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                }
            }
        }
        // On foot to a ride's stop, and at it: each of its lines, and a way to take one alone
        val toRide = phase is JourneyPhase.Waiting || (phase is JourneyPhase.Walking && phase.leg.toStop && journey.pendingRide != null)
        if (journey.planned && toRide) {
            item(key = "lines") {
                RideLines(journey, (rideBuses ?: RideBuses(journey.pendingRide ?: -1)).lines(journey, at, awaited), words, onChooseLine)
            }
        }
        if (phase is JourneyPhase.Riding) {
            // A planner trip gets off where its plan says; a single ride where the rider taps
            val plan = phase.trip.plan
            rideStops(phase.trip, at, words, if (journey.planned) null else { index -> change(plan, index) })
        }
        val ahead = journey.ahead
        if (ahead.isNotEmpty()) {
            item {
                LabSectionLabel(
                    stringResource(R.string.trip_then_label),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(ahead.size, key = { "ahead:$it" }) { i -> AheadRow(ahead[i], words, journey.chosenLines[journey.index + 1 + i]) }
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

/** "As you board, tap the line you took", then an "I took the E21" button for each of [lines]; nothing with none */
@Composable
private fun TookButtons(lines: List<String>, onTookLine: (String) -> Unit) {
    if (lines.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.trip_took_it_hint), style = LabText.statusLine(LabTheme.palette))
    for (line in lines) {
        Spacer(Modifier.height(8.dp))
        LabOutlinedButton(
            onClick = { onTookLine(line) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("trip_took_$line"),
        ) { Text(stringResource(R.string.trip_took_line, line)) }
    }
}

/**
 * The lines of the ride ahead and their next buses at the stop: with
 * more than one, chips to take one alone or any, and a row per line, the
 * one chosen alone once one is; a tap on a row chooses its line too
 */
@Composable
private fun RideLines(journey: Journey, lines: List<LineTimes>, words: TripWords, onChoose: (String?) -> Unit) {
    val p = LabTheme.palette
    val chosen = journey.chosenLine
    val several = journey.pendingCandidates.size > 1
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("trip_lines")) {
        LabSectionLabel(stringResource(R.string.trip_lines_label), modifier = Modifier.padding(bottom = 6.dp))
        if (several) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LineChip(stringResource(R.string.trip_line_any), selected = chosen == null, tag = "trip_line_any") { onChoose(null) }
                for (line in journey.pendingCandidates.map { it.line }) {
                    LineChip(line, selected = chosen == line, tag = "trip_line_$line") { onChoose(line) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (chosen == null) stringResource(R.string.trip_lines_hint) else stringResource(R.string.trip_line_only, chosen),
                style = LabText.statusLine(p),
            )
        }
        for (row in lines) {
            if (chosen != null && row.line != chosen) continue
            LineRow(row, words, onClick = if (several && chosen == null) ({ onChoose(row.line) }) else null)
        }
    }
}

/** One choice among the lines of a ride, lit when [selected] */
@Composable
private fun LineChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val p = LabTheme.palette
    Box(
        Modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp)
            .background(if (selected) p.accent else Color.Transparent)
            .border(1.dp, p.accent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, textAlign = TextAlign.Center, style = LabText.mono(p, size = 13f, color = if (selected) p.paper else p.accent))
    }
}

/** "T52   16 min · 31 min · 47 min", those the rider cannot make greyed and said so */
@Composable
private fun LineRow(row: LineTimes, words: TripWords, onClick: (() -> Unit)?) {
    val p = LabTheme.palette
    val resources = LocalResources.current
    val atStop = stringResource(R.string.at_the_stop).lowercase()
    val none = stringResource(R.string.no_buses_coming)
    val text = buildAnnotatedString {
        if (row.times.isEmpty()) {
            withStyle(SpanStyle(color = p.muted)) { append(none) }
        }
        for ((i, time) in row.times.withIndex()) {
            if (i > 0) append(" · ")
            val shown = if (time.atStop) atStop else words.minutes(time.minutes, time.estimated)
            if (time.reachable) append(shown) else withStyle(SpanStyle(color = p.muted)) { append(shown) }
        }
    }
    val missed = row.times.count { !it.reachable }
    val spoken = buildList {
        add(row.line)
        if (row.times.isEmpty()) add(none)
        for (time in row.times) {
            add(if (time.atStop) atStop else resources.getQuantityString(R.plurals.trip_minutes_spoken, time.minutes, time.minutes))
        }
        if (missed > 0) add(resources.getQuantityString(R.plurals.trip_line_missed, missed, missed))
    }.joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("trip_line_row_${row.line}")
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            // Read out whole, the times as minutes and those out of reach said so
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) onClick(label = null) { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.line, style = LabText.mono(p, size = 15f, color = p.accent), modifier = Modifier.widthIn(min = 56.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(color = p.ink), modifier = Modifier.weight(1f))
    }
}

/** The line of [lines] whose awaited bus just left the stop, which the trip asks about; none when it asks nothing */
private fun askingLine(awaited: List<AwaitedLine>, lines: List<String>): String? = awaited.firstOrNull { it.asking && it.line in lines }?.line

/** "Did you take the T31?" with Yes and No, while a bus of [lines] awaited has just left the stop */
@Composable
private fun DidYouTake(awaited: List<AwaitedLine>, lines: List<String>, words: TripWords, onYes: (String) -> Unit, onNo: (String) -> Unit) {
    val line = askingLine(awaited, lines) ?: return
    Spacer(Modifier.height(8.dp))
    Text(
        words.didYouTake(line),
        style = MaterialTheme.typography.titleMedium.copy(color = LabTheme.palette.ink),
        modifier = Modifier.testTag("trip_asking"),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabFilledButton(onClick = { onYes(line) }, modifier = Modifier.weight(1f).testTag("trip_asking_yes")) { Text(words.yes) }
        LabOutlinedButton(onClick = { onNo(line) }, modifier = Modifier.weight(1f).testTag("trip_asking_no")) { Text(words.no) }
    }
}

/** The buses awaited, soonest first: "E21 2 min · T31 5 min"; nothing with none */
@Composable
private fun AwaitedTimes(awaited: List<AwaitedLine>, at: Long, words: TripWords) {
    val coming = awaited.filter { !it.asking }
    if (coming.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Text(
        coming.joinToString(" · ") { words.lineMinutes(it, at) },
        style = MaterialTheme.typography.bodyLarge.copy(color = LabTheme.palette.ink),
        modifier = Modifier.testTag("trip_awaited"),
    )
}

@Composable
private fun Source(text: Int) {
    Spacer(Modifier.height(4.dp))
    Text(stringResource(text), style = LabText.statusLine(LabTheme.palette))
}

/**
 * Why the trip goes by the estimate: the permission approximate alone, a
 * good fix had but none fresh, or none yet
 */
@StringRes
private fun estimateSource(journey: Journey): Int = when {
    journey.approximate -> R.string.trip_by_estimate_approximate
    journey.fix != null -> R.string.trip_by_estimate_stale
    else -> R.string.trip_by_estimate
}

@Composable
private fun RideHeading(journey: Journey, trip: Trip, at: Long, words: TripWords) {
    val progress = trip.progress(at)
    val plan = trip.plan
    Heading(words.rideHeading(plan, progress.stopsLeft))
    if (progress.stopsLeft > 0) {
        Detail(words.headingDetail(trip.minutesTo(plan.target, at), progress.source == TripSource.ESTIMATE, progress.stopsLeft))
    }
    Source(
        when (progress.source) {
            TripSource.FIX -> R.string.trip_by_fix
            TripSource.BUS -> R.string.trip_by_bus
            TripSource.ESTIMATE -> estimateSource(journey)
        },
    )
}

/**
 * Get off at another stop of a ride's plan, with an Undo that puts back
 * where the rider got off before, or just following the ride
 */
@Composable
private fun rememberAlightChange(onAlight: (Int?) -> Unit, snackbars: LabSnackbars): (TripPlan, Int) -> Unit {
    val resources = LocalResources.current
    val time = rememberUndoTime()
    val alight by rememberUpdatedState(onAlight)
    return remember(resources, snackbars, time) {
        { plan, index ->
            val before = plan.alight
            alight(index)
            snackbars.showTimed(
                resources.getString(R.string.trip_get_off_now, plan.stops[index].name),
                resources.getString(R.string.undo),
                time,
            ) { alight(before) }
        }
    }
}

/**
 * The ride's stops ahead, each with its time and the one to get off at
 * marked; with [onChange], a single ride's, the others a tap to get off
 * there instead
 */
private fun androidx.compose.foundation.lazy.LazyListScope.rideStops(trip: Trip, at: Long, words: TripWords, onChange: ((Int) -> Unit)?) {
    val plan = trip.plan
    val progress = trip.progress(at)
    val estimated = progress.source == TripSource.ESTIMATE
    item {
        LabSectionLabel(
            stringResource(R.string.trip_stops_ahead),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
    if (onChange != null) {
        item {
            Text(
                stringResource(R.string.trip_tap_to_get_off),
                style = LabText.statusLine(LabTheme.palette),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
    }
    val ahead = ((progress.stop + 1).coerceAtMost(plan.stops.lastIndex)..plan.stops.lastIndex).toList()
    items(ahead, key = { "stop:$it" }) { index ->
        val p = LabTheme.palette
        val stop = plan.stops[index]
        val alighting = index == plan.alight
        val time = words.minutes(trip.minutesTo(index, at), estimated)
        val tap = if (onChange != null && !alighting) {
            Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.trip_get_off_at_action, stop.name)) { onChange(index) }
        } else {
            Modifier
        }
        LabListTile(
            title = stop.name,
            subtitle = if (alighting) "$time · ${stringResource(R.string.trip_you_get_off)}" else time,
            contentColor = if (alighting) p.accent else null,
            titleWeight = if (alighting) FontWeight.Bold else FontWeight.Medium,
            modifier = tap
                // Chosen, not disabled
                .semantics { selected = alighting }
                .heightIn(min = 48.dp)
                .testTag(if (alighting) "trip_alight" else "trip_stop_${stop.stopId}"),
        )
    }
}

/** A trip's words, in the language the screen shows */
@Composable
internal fun rememberTripWords(): TripWords {
    val resources = LocalResources.current
    return remember(resources) { TripWords(resources) }
}

/** A leg still to come, in muted words */
@Composable
private fun AheadRow(leg: JourneyLeg, words: TripWords, chosen: String?) {
    val p = LabTheme.palette
    val text = when (leg) {
        is JourneyLeg.Walk -> stringResource(R.string.trip_ahead_walk, leg.toName, minutes(leg.walk.duration))
        is JourneyLeg.Ride -> pluralStringResource(
            R.plurals.trip_ahead_ride,
            leg.ride.stopCount,
            words.lines(chosen?.let { listOf(it) } ?: leg.ride.lines),
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
    // A line chosen for the ride ahead frames its whole route, with the
    // rider and the stop, and the map rests there, as moved by hand, with
    // the button to follow again; any line, or the next leg, follows again
    val chosen = journey.chosenLine
    val opened = remember { journey.index }
    var framedFor by remember { mutableStateOf(journey.index to chosen) }
    var map by remember { mutableStateOf<LabMapState?>(null) }
    var follow by remember { mutableStateOf(MapFollow(following = chosen == null)) }
    if (framedFor != journey.index to chosen) {
        val sameLeg = framedFor.first == journey.index
        framedFor = journey.index to chosen
        map = null
        follow = MapFollow(following = chosen == null || (!sameLeg && journey.index != opened))
    }
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
    TripLineRole.RIDE_CHOSEN -> LabMapPolyline(line.id, line.points, tiles.accent, width = 5.dp, borderColor = tiles.paper, borderWidth = 1.5.dp)
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
