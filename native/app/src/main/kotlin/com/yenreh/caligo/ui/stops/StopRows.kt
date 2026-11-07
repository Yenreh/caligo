package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * coming, opening its details
 */
@Composable
fun NearbyStopRow(stop: NearbyStop, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lines = stop.arrivals.map { it.line }.distinct().take(4).joinToString(", ")
    LabListTile(
        title = stop.name,
        subtitle = metersAway(stop) + if (lines.isEmpty()) "" else " · $lines",
        leading = painterResource(R.drawable.ic_signpost),
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag("nearby_stop"),
        trailing = { Chevron() },
    )
}

/** The trailing arrow of a row that opens something */
@Composable
fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = LabTheme.palette.muted,
    )
}
