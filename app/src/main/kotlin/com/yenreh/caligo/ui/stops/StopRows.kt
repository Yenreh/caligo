package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.theme.LabTheme
import kotlin.math.roundToInt

/** How far a stop is, as the lists put it */
@Composable
fun metersAway(stop: NearbyStop): String = stringResource(R.string.meters_away, stop.distanceMeters.roundToInt())

/**
 * A stop found around a point: its distance and up to four of the lines
 * coming, opening its details. A [selected] row, the stop picked on the
 * map, stands out on paper2 in accent, as a selected Flutter ListTile
 * takes the theme's primary colour.
 */
@Composable
fun NearbyStopRow(stop: NearbyStop, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val p = LabTheme.palette
    val lines = stop.arrivals.map { it.line }.distinct().take(4).joinToString(", ")
    val tone = if (selected) p.accent else null
    LabListTile(
        title = stop.name,
        subtitle = metersAway(stop) + if (lines.isEmpty()) "" else " · $lines",
        leading = painterResource(R.drawable.ic_signpost),
        contentColor = tone,
        modifier = modifier
            .then(if (selected) Modifier.background(p.paper2) else Modifier)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("nearby_stop"),
        trailing = { Chevron(tone ?: p.muted) },
    )
}

/** The trailing arrow of a row that opens something */
@Composable
fun Chevron(tint: Color = LabTheme.palette.muted) {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = tint,
    )
}
