package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.ui.common.LiveClock
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.rememberLoader
import com.yenreh.caligo.ui.lab.LabBottomSheet
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.lab.LabLineChip
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** What the details sheet asks of the screen that opens it */
class StopDetailsActions(
    /** Fresh arrivals of the stop, from one request */
    val refresh: suspend (NearbyStop) -> NearbyStop,
    /** Lines serving a stop, by its id */
    val loadLines: suspend (String) -> List<StopLine>,
    /** Save the stop under the label given, empty for none; true once saved */
    val save: suspend (customName: String) -> Boolean,
)

/**
 * Details of a stop: the buses on their way and the lines serving it.
 *
 * Opens from any stop list so a place can be checked without saving it,
 * and refreshes on demand since a look is often all it gets. Saving asks
 * for a label first, then closes the sheet once the stop is saved.
 *
 * [fetchOnOpen] is for somewhere that knows the stop but not its buses,
 * such as a line's route: ask for them at once, with no distance to show.
 * The Flutter sheet's "Go here", to the trip planner, comes with it.
 */
@Composable
fun StopDetailsSheet(
    stop: NearbyStop,
    isSaved: Boolean,
    actions: StopDetailsActions,
    onDismiss: () -> Unit,
    fetchOnOpen: Boolean = false,
) {
    LabBottomSheet(onDismiss = onDismiss, dragHandle = true) {
        val lines = rememberLoader(stop.id) { actions.loadLines(stop.id) }
        StopDetailsContent(stop, isSaved, lines.state, actions, onDismiss, fetchOnOpen)
    }
}

/** The sheet's content, apart so it shows without one */
@Composable
fun StopDetailsContent(
    initial: NearbyStop,
    isSaved: Boolean,
    lines: Loaded<List<StopLine>>,
    actions: StopDetailsActions,
    onDismiss: () -> Unit,
    fetchOnOpen: Boolean = false,
) {
    val p = LabTheme.palette
    val scope = rememberCoroutineScope()
    var stop by remember { mutableStateOf(initial) }
    // When the arrivals on screen were fetched; null until a refresh, since
    // the list the sheet came from does not say
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(fetchOnOpen) }
    var failed by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }

    suspend fun refresh() {
        refreshing = true
        try {
            stop = actions.refresh(stop)
            updatedAt = System.currentTimeMillis()
            failed = false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Keep the arrivals already on screen and say they are not fresh
            failed = true
        } finally {
            refreshing = false
        }
    }

    if (fetchOnOpen) LaunchedEffect(Unit) { refresh() }

    LiveClock {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .testTag("stop_details"),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stop.name, style = MaterialTheme.typography.titleLarge.copy(color = p.ink))
                    Spacer(Modifier.height(2.dp))
                    val at = updatedAt
                    Text(
                        text = listOfNotNull(
                            if (fetchOnOpen) null else metersAway(stop),
                            at?.let { stringResource(R.string.updated_at, hourMinute(it)) },
                        ).joinToString(" · "),
                        style = LabText.statusLine(p),
                    )
                    if (failed) {
                        Text(
                            stringResource(R.string.could_not_load_arrivals),
                            style = LabText.statusLine(p).copy(color = p.crit),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                LabIconButton(
                    icon = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.update_arrivals),
                    onClick = { scope.launch { refresh() } },
                    isLoading = refreshing,
                )
            }
            Spacer(Modifier.height(16.dp))
            // A rule, not a divider: the top border of an empty box
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(p.rule),
            )
            when {
                // Nothing known yet: the first answer is on its way
                fetchOnOpen && updatedAt == null && !failed -> Progress(Modifier.padding(vertical = 16.dp))
                stop.arrivals.isEmpty() -> Text(
                    stringResource(R.string.no_buses_coming),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(color = p.muted),
                )
                else -> stop.arrivals.take(6).forEachIndexed { i, arrival ->
                    if (i > 0) LabDivider()
                    ArrivalRow(arrival, padding = PaddingValues(vertical = 8.dp))
                }
            }
            LabDivider()
            Spacer(Modifier.height(16.dp))
            LabSectionLabel(stringResource(R.string.lines_serving))
            Spacer(Modifier.height(10.dp))
            val listed = lines.valueOrNull
            when {
                lines == Loaded.Loading -> Progress(Modifier.padding(vertical = 8.dp))
                // An error says as much as an empty list
                listed.isNullOrEmpty() -> Text(
                    stringResource(R.string.no_lines_for_stop),
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (line in listed) LabTooltip(line.name) { LabLineChip(line.shortName) }
                }
            }
            Spacer(Modifier.height(24.dp))
            LabFilledButton(
                onClick = { naming = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_stop"),
                enabled = !isSaved,
            ) {
                Icon(
                    painterResource(if (isSaved) R.drawable.ic_star else R.drawable.ic_star_outline),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (isSaved) R.string.stop_already_saved else R.string.save_as_favorite))
            }
        }
    }

    if (naming) {
        StopNameDialog(
            title = stringResource(R.string.save_as_favorite),
            realName = stop.name,
            onDismiss = { naming = false },
            onSave = { customName ->
                naming = false
                // Closed once saved; a sheet already closed stays so
                scope.launch { if (actions.save(customName)) onDismiss() }
            },
        )
    }
}

/** A thin bar for something on its way, as the theme's LinearProgressIndicator */
@Composable
private fun Progress(modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
        color = p.accent,
        trackColor = p.ruleSoft,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
    )
}

/** As intl's DateFormat.Hm, the same in both languages */
private fun hourMinute(epochMillis: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(epochMillis))

/** A stop whose details show, and the point it was found from */
class OpenStop(val stop: NearbyStop, val anchorLatitude: Double, val anchorLongitude: Double)

/**
 * The details of [open], with what the activity's [StopsViewModel] knows:
 * whether it is saved already, its fresh arrivals and its lines
 */
@Composable
fun StopDetailsFor(open: OpenStop, viewModel: StopsViewModel, onDismiss: () -> Unit) {
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    val stop = open.stop
    StopDetailsSheet(
        stop = stop,
        isSaved = stops.favorites.any { it.stopId == stop.id },
        actions = remember(open, viewModel) {
            StopDetailsActions(
                refresh = { viewModel.refreshStop(it, open.anchorLatitude, open.anchorLongitude) },
                loadLines = viewModel::linesByStop,
                save = { customName ->
                    viewModel.addFavorite(
                        id = stop.id,
                        stopId = stop.id,
                        name = stop.name,
                        anchorLatitude = open.anchorLatitude,
                        anchorLongitude = open.anchorLongitude,
                        customName = customName,
                    ).await()
                },
            )
        },
        onDismiss = onDismiss,
    )
}
