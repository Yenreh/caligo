package com.yenreh.caligo.ui.trips

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.TripSource
import com.yenreh.caligo.trips.EndedTrip
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.LiveClock
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

    data class Running(val trip: Trip) : TripContent

    /** The trip ended, as [ended] says; null with nothing to tell */
    data class Ended(val ended: EndedTrip?) : TripContent
}

/** The trip screen with its view model; [onBack] closes it */
@Composable
fun TripRoute(viewModel: TripViewModel, onBack: () -> Unit) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val running by viewModel.trips.running.collectAsStateWithLifecycle()
    val ended by viewModel.trips.ended.collectAsStateWithLifecycle()
    val took = viewModel.took
    val trip = running?.takeIf { took == null || it.plan.took == took }
    val content = when {
        !screen.started -> when (val plan = screen.plan) {
            Loaded.Loading -> TripContent.Loading
            is Loaded.Failed -> TripContent.Failed
            is Loaded.Ready -> plan.value?.let { TripContent.Choosing(it, screen.starting) }
                ?: TripContent.NotOnRoute(took?.line.orEmpty())
        }
        trip != null -> TripContent.Running(trip)
        else -> TripContent.Ended(ended?.takeIf { took == null || it.trip.plan.took == took })
    }
    val context = LocalContext.current
    TripScreen(
        content = content,
        title = (took ?: trip?.plan?.took ?: ended?.trip?.plan?.took)?.let { stringResource(R.string.trip_title, it.line, it.destination) }
            ?: stringResource(R.string.trip_channel),
        subtitle = (took ?: trip?.plan?.took)?.let { stringResource(R.string.trip_from, it.stopName) },
        onBack = onBack,
        onRetry = viewModel::retry,
        onChoose = viewModel::choose,
        onAlight = viewModel::alightAt,
        onFinish = {
            viewModel.finish()
            onBack()
        },
        message = screen.message,
        onMessageSettings = { message ->
            when (message) {
                TripMessage.NEEDS_NOTIFICATIONS -> viewModel.openNotificationSettings()
                TripMessage.NEEDS_LOCATION -> {
                    viewModel.dismissMessage()
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                TripMessage.LOCATION_OFF -> {
                    viewModel.dismissMessage()
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        },
        onMessageDismiss = viewModel::dismissMessage,
    )
}

/**
 * The trip screen: where to get off, then the ride's stops ahead with
 * their times, the one to get off at, which a tap on another moves, and
 * Finish. The map comes with 5.2b, between the heading and the stops.
 */
@Composable
fun TripScreen(
    content: TripContent,
    title: String,
    modifier: Modifier = Modifier,
    /** Where the ride was boarded: "From Plaza de Cayzedo A1" */
    subtitle: String? = null,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onChoose: (Int?) -> Unit = {},
    onAlight: (Int?) -> Unit = {},
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
                is TripContent.Running -> Running(content.trip, subtitle, onAlight, body)
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
    message?.let { MessageDialog(it, onSettings = { onMessageSettings(it) }, onDismiss = onMessageDismiss) }
}

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

/** The ride as it goes: the heading, then the stops ahead with their times */
@Composable
private fun Running(trip: Trip, from: String?, onAlight: (Int?) -> Unit, modifier: Modifier) {
    val p = LabTheme.palette
    val now = LocalNow.current
    watchLiveClock()
    val at = now()
    val progress = trip.progress(at)
    val plan = trip.plan
    val estimated = progress.source == TripSource.ESTIMATE
    fun about(minutes: Int) = if (estimated) "~$minutes" else "$minutes"
    val target = plan.stops[plan.target]
    LazyColumn(modifier.testTag("trip_running")) {
        item {
            LabPanel(
                modifier = Modifier.padding(16.dp),
                accentTop = true,
            ) {
                val heading = when {
                    plan.alight != null && progress.stopsLeft == 0 -> stringResource(R.string.trip_heading_here)
                    plan.alight != null -> stringResource(R.string.trip_heading_get_off, target.name)
                    else -> stringResource(R.string.trip_heading_follow, target.name)
                }
                Text(
                    heading,
                    style = MaterialTheme.typography.titleLarge.copy(color = p.ink),
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("trip_heading"),
                )
                if (progress.stopsLeft > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pluralStringResource(
                            R.plurals.trip_heading_detail,
                            progress.stopsLeft,
                            about(trip.minutes(trip.millisTo(plan.target, at))),
                            progress.stopsLeft,
                        ),
                        style = MaterialTheme.typography.bodyLarge.copy(color = p.ink, fontWeight = FontWeight.Medium),
                        modifier = Modifier.testTag("trip_detail"),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        when (progress.source) {
                            TripSource.FIX -> R.string.trip_by_fix
                            TripSource.BUS -> R.string.trip_by_bus
                            TripSource.ESTIMATE -> R.string.trip_by_estimate
                        },
                    ),
                    style = LabText.statusLine(p),
                )
                if (from != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(from, style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                }
            }
        }
        item {
            LabSectionLabel(
                stringResource(R.string.trip_stops_ahead),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        val ahead = ((progress.stop + 1).coerceAtMost(plan.stops.lastIndex)..plan.stops.lastIndex).toList()
        items(ahead, key = { "stop:$it" }) { index ->
            val stop = plan.stops[index]
            val alighting = index == plan.alight
            val minutes = trip.minutes(trip.millisTo(index, at))
            val time = stringResource(R.string.trip_minutes, about(minutes))
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
}

@Composable
private fun endedText(ended: EndedTrip?): String = when (ended?.ending) {
    TripEnding.REACHED -> stringResource(R.string.trip_ended_reached, ended.trip.plan.stops[ended.trip.plan.target].name)
    TripEnding.DISMISSED -> stringResource(R.string.trip_ended_dismissed)
    TripEnding.TIME_LIMIT -> stringResource(R.string.trip_ended_time_limit)
    TripEnding.OFF_ROUTE -> stringResource(R.string.trip_ended_off_route)
    TripEnding.BLOCKED -> stringResource(R.string.trip_ended_blocked)
    TripEnding.NO_LOCATION -> stringResource(R.string.trip_ended_no_location)
    TripEnding.REPLACED -> stringResource(R.string.trip_ended_replaced)
    TripEnding.FINISHED, null -> stringResource(R.string.trip_ended_finished)
}

/** Why the trip did not start, with the way to the settings that fix it */
@Composable
private fun MessageDialog(message: TripMessage, onSettings: () -> Unit, onDismiss: () -> Unit) {
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
