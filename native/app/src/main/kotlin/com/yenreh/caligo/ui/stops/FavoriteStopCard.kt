package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
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
    val list = arrivals?.let(stop::pick)
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
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
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
                    modifier = Modifier.weight(1f),
                    style = LabText.statusLine(p).copy(color = p.crit, fontSize = 13.sp),
                )
                if (onRelink != null) {
                    LabTextButton(onClick = onRelink) { Text(stringResource(R.string.relink_stop)) }
                }
            }
        }
        LabDivider()
        when {
            list == null && isLoading -> LinearProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .fillMaxWidth()
                    .height(2.dp),
                color = p.accent,
                trackColor = p.ruleSoft,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
            list == null -> Text(stringResource(R.string.arrivals_unknown), Modifier.padding(16.dp), style = muted)
            list.isEmpty() -> Text(
                // Buses may be coming, just none of the lines followed
                text = stringResource(
                    if (arrivals.orEmpty().isNotEmpty()) R.string.no_buses_of_lines else R.string.no_buses_coming,
                ),
                modifier = Modifier.padding(16.dp),
                style = muted,
            )
            else -> Column(Modifier.padding(vertical = 4.dp)) {
                list.take(5).forEachIndexed { i, arrival ->
                    if (i > 0) LabDivider(indent = 16.dp)
                    ArrivalRow(arrival, showStopName = stop.isArea)
                }
            }
        }
    }
}

/** A single line, destination and countdown row */
@Composable
fun ArrivalRow(
    arrival: BusArrival,
    modifier: Modifier = Modifier,
    showStopName: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    Row(
        modifier = modifier
            .testTag("arrival")
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabLineChip(arrival.line)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = arrival.destination,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showStopName && arrival.stopName.isNotEmpty()) {
                Text(
                    text = arrival.stopName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Countdown(arrival)
    }
}

/**
 * Minutes left, the only part of an arrival that changes on its own: it
 * alone follows the live clock above
 */
@Composable
private fun Countdown(arrival: BusArrival) {
    watchLiveClock()
    val p = LabTheme.palette
    val minutes = arrival.minutesUntilArrival()
    Text(
        text = if (minutes == 0) stringResource(R.string.arriving_now) else stringResource(R.string.minutes_short, minutes),
        style = LabText.mono(p, size = 16f, color = if (minutes <= 2) p.ok else p.ink),
    )
}
