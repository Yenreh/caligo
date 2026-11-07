package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.rememberLoader
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.launch

/**
 * Stops belonging to a station.
 *
 * A station spans several platforms, and following all of them at once
 * is usually noise, so each one can be saved on its own; the star saves
 * the whole area, and closes the screen through [onDone] once saved.
 */
@Composable
fun StationStopsScreen(
    station: Station,
    viewModel: StopsViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LabTheme.palette
    val scope = rememberCoroutineScope()
    // A list with no map: the stops need no placing beyond what is known
    val stops = rememberLoader(station) { viewModel.nearbyAt(station.latitude, station.longitude) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<OpenStop?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LabTopBar(station.name, onBack = onBack) {
                val label = stringResource(R.string.save_this_area)
                LabTooltip(label) {
                    IconButton(
                        onClick = { naming = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("save_area"),
                        shape = RectangleShape,
                    ) {
                        Icon(painterResource(R.drawable.ic_star_outline), contentDescription = label, tint = p.ink)
                    }
                }
            }
        },
    ) { padding ->
        val body = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
            .fillMaxSize()
        when (val state = stops.state) {
            Loaded.Loading -> Box(body, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(36.dp), color = p.accent, strokeWidth = 4.dp)
            }
            is Loaded.Failed -> Centered(stringResource(R.string.could_not_load_arrivals), body)
            is Loaded.Ready if state.value.isEmpty() -> Centered(stringResource(R.string.no_stops_nearby), body)
            is Loaded.Ready -> LazyColumn(body.testTag("station_stops")) {
                itemsIndexed(state.value) { i, stop ->
                    if (i > 0) LabDivider()
                    NearbyStopRow(stop, onClick = { details = OpenStop(stop, station.latitude, station.longitude) })
                }
            }
        }
    }

    if (naming) {
        StopNameDialog(
            title = stringResource(R.string.save_this_area),
            realName = station.name,
            onDismiss = { naming = false },
            onSave = { customName ->
                naming = false
                scope.launch {
                    val saved = viewModel.addFavorite(
                        id = "station-${station.id}",
                        name = station.name,
                        anchorLatitude = station.latitude,
                        anchorLongitude = station.longitude,
                        customName = customName,
                    ).await()
                    if (saved) onDone()
                }
            },
        )
    }

    details?.let { open -> StopDetailsFor(open, viewModel, onDismiss = { details = null }) }
}

@Composable
private fun Centered(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = LabTheme.palette.ink))
    }
}
