package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.ArrivalPhase
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.FadingRow
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.asLiveRows
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.shown
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.ui.common.LocalNow
import com.yenreh.caligo.ui.common.watchLiveClock
import com.yenreh.caligo.ui.lab.LabChip
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.lab.LabLineChip
import com.yenreh.caligo.ui.lab.LabPanel
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * One favorite stop with its next buses. The stops screen adds its
 * actions: each one null hides its button, as the home has none.
 */
@Composable
fun FavoriteStopCard(
    stop: FavoriteStop,
    // Null while the stop has no answer yet
    arrivals: List<BusArrival>?,
    modifier: Modifier = Modifier,
    // The buses of recent answers, so those reaching the stop fade out;
    // null shows [arrivals] as they are
    fading: FadingArrivals? = null,
    // A refresh on its way, which tells an empty card apart from one
    // still waiting for its first answer
    isLoading: Boolean = false,
    onRemove: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    // Offered when the stop went missing
    onRelink: (() -> Unit)? = null,
    // Flips whether the home screen lists the stop
    onToggleHome: (() -> Unit)? = null,
) {
    val p = LabTheme.palette
    val muted = MaterialTheme.typography.bodyMedium.copy(color = p.muted)
    val hasActions = onRename != null || onRemove != null || onToggleHome != null

    LabPanel(modifier = modifier.testTag("favorite_stop"), accentTop = true, padding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = if (hasActions) 12.dp else 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stop.displayName,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                stop.secondaryName?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = LabText.statusLine(p).copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (stop.lines.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LabChip(stop.lines.joinToString(" · "))
                }
                if (onToggleHome != null && !stop.showOnHome) {
                    Spacer(Modifier.height(6.dp))
                    LabChip(stringResource(R.string.hidden_from_home).uppercase())
                }
            }
            if (onToggleHome != null) {
                Spacer(Modifier.width(8.dp))
                LabIconButton(
                    icon = painterResource(if (stop.showOnHome) R.drawable.ic_home else R.drawable.ic_home_outlined),
                    contentDescription = stringResource(
                        if (stop.showOnHome) R.string.hide_from_home else R.string.show_on_home,
                    ),
                    onClick = onToggleHome,
                    active = stop.showOnHome,
                )
            }
            if (onRename != null) {
                Spacer(Modifier.width(6.dp))
                LabIconButton(
                    icon = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit_stop),
                    onClick = onRename,
                )
            }
            if (onRemove != null) {
                Spacer(Modifier.width(6.dp))
                LabIconButton(
                    icon = painterResource(R.drawable.ic_star),
                    contentDescription = stringResource(R.string.remove_stop),
                    onClick = onRemove,
                )
            }
        }
        if (stop.looksGone) {
            LabDivider()
            // The button below the notice once large text leaves no room beside it
            FlowRow(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .widthIn(min = NOTICE_MIN_WIDTH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(p.crit),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.stop_not_reported),
                        style = LabText.statusLine(p).copy(color = p.crit, fontSize = 13.sp),
                    )
                }
                if (onRelink != null) {
                    LabTextButton(onClick = onRelink) { Text(stringResource(R.string.relink_stop)) }
                }
            }
        }
        LabDivider()
        when {
            arrivals == null && isLoading -> LinearProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .fillMaxWidth()
                    .height(2.dp),
                color = p.accent,
                trackColor = p.ruleSoft,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
            arrivals == null -> Text(stringResource(R.string.arrivals_unknown), Modifier.padding(16.dp), style = muted)
            else -> FavoriteArrivals(stop, arrivals, fading)
        }
    }
}

/**
 * A favorite's rows as they read now. Alone on the live clock: each beat
 * works the rows out again, with what has reached the stop or passed.
 */
@Composable
private fun FavoriteArrivals(stop: FavoriteStop, arrivals: List<BusArrival>, fading: FadingArrivals?) {
    watchLiveClock()
    val now = LocalNow.current()
    val rows = (fading?.rows(now) ?: arrivals.asLiveRows())
        .filter { stop.picks(it.bus.line) }
        .shown(FAVORITE_ROWS)
    if (rows.none { it.phase !is ArrivalPhase.Passed }) {
        Text(
            // Buses may be coming, just none of the lines followed
            text = stringResource(if (arrivals.isNotEmpty()) R.string.no_buses_of_lines else R.string.no_buses_coming),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = LabTheme.palette.muted),
        )
        if (rows.isEmpty()) return
        LabDivider(indent = 16.dp)
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) LabDivider(indent = 16.dp)
            ArrivalRow(
                row.bus,
                phase = row.phase,
                showStopName = stop.isArea,
                bell = { RowBell(row, row.awaitedAt(stop), fading) },
            )
        }
    }
}

/**
 * A single line, destination and countdown row; at the stop or passed,
 * a label in place of the countdown, and passed, greyed
 */
@Composable
fun ArrivalRow(
    arrival: BusArrival,
    modifier: Modifier = Modifier,
    phase: ArrivalPhase = ArrivalPhase.Live,
    showStopName: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    // The bell after the countdown, when the list has them
    bell: (@Composable () -> Unit)? = null,
) {
    val p = LabTheme.palette
    val passed = phase is ArrivalPhase.Passed
    val direction = LocalLayoutDirection.current
    // A bell brings its own inset: the row's end gives up as much of it
    val end = padding.calculateEndPadding(direction).let { if (bell != null) (it - BELL_INSET).coerceAtLeast(0.dp) else it }
    Row(
        modifier = modifier
            .testTag(if (passed) "arrival_passed" else "arrival")
            .padding(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = end,
                bottom = padding.calculateBottomPadding(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabLineChip(arrival.line, color = if (passed) p.muted else p.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = arrival.destination,
                style = MaterialTheme.typography.bodyMedium.let { if (passed) it.copy(color = p.muted) else it },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showStopName && arrival.stopName.isNotEmpty()) {
                Text(
                    text = arrival.stopName,
                    style = MaterialTheme.typography.bodySmall.let { if (passed) it.copy(color = p.muted) else it },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (phase) {
            ArrivalPhase.Live -> Countdown(arrival)
            // At most half the row, on two lines when large text needs it,
            // so the destination keeps the other half
            ArrivalPhase.AtStop -> Text(
                text = stringResource(R.string.at_the_stop),
                modifier = Modifier.atMostHalf(),
                textAlign = TextAlign.End,
                style = LabText.mono(p, size = 14f, color = p.ok),
            )
            is ArrivalPhase.Passed -> Text(
                text = stringResource(R.string.passed_minutes_ago, phase.minutes),
                modifier = Modifier.atMostHalf(),
                textAlign = TextAlign.End,
                style = LabText.mono(p, size = 14f, color = p.muted),
            )
        }
        bell?.invoke()
    }
}

/**
 * The bell of [row], waiting for [bus]: none on a row that passed, only
 * its room, so the countdowns above line up
 */
@Composable
fun RowBell(row: FadingRow, bus: AwaitedBus?, seed: FadingArrivals?) {
    if (LocalWaitBells.current == null) return
    if (row.phase is ArrivalPhase.Passed) {
        Spacer(Modifier.width(BELL_SIZE))
        return
    }
    WaitBell(bus, row.bus.line, seed)
}

/**
 * The bus of this row on [favorite], to wait for; null while its stop is
 * not known, as for an area's rows kept from the last run
 */
fun FadingRow.awaitedAt(favorite: FavoriteStop): AwaitedBus? {
    val stopId = stopId.ifEmpty { favorite.stopId ?: return null }
    return AwaitedBus(
        stopId = stopId,
        // As the card titles it: the user's own name when it has one
        stopName = favorite.displayName,
        bus = bus,
        anchor = GeoPoint(favorite.anchorLatitude, favorite.anchorLongitude),
        area = favorite.isArea,
    )
}

/**
 * Minutes left, the only part of a live arrival that changes on its own:
 * it alone follows the live clock above
 */
@Composable
private fun Countdown(arrival: BusArrival) {
    watchLiveClock()
    val p = LabTheme.palette
    val minutes = arrival.minutesUntilArrival(LocalNow.current())
    Text(
        text = if (minutes == 0) stringResource(R.string.arriving_now) else stringResource(R.string.minutes_short, minutes),
        style = LabText.mono(p, size = 16f, color = if (minutes <= 2) p.ok else p.ink),
    )
}

/** No wider than half of what the row has left for it */
private fun Modifier.atMostHalf() = layout { measurable, constraints ->
    val placeable = if (constraints.hasBoundedWidth) {
        val half = constraints.maxWidth / 2
        measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, half), maxWidth = half))
    } else {
        measurable.measure(constraints)
    }
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The room around a bell's icon, which a row's end padding need not repeat */
private val BELL_INSET = 12.dp

/** Rows a favorite's card lists, besides those passed */
private const val FAVORITE_ROWS = 5

/** The least the missing stop's notice keeps beside its button */
private val NOTICE_MIN_WIDTH = 160.dp
