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
import com.yenreh.caligo.domain.ArrivalPhase
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.FadingRow
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.shown
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.common.LiveClock
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.LocalNow
import com.yenreh.caligo.ui.common.rememberLoader
import com.yenreh.caligo.ui.common.watchLiveClock
import com.yenreh.caligo.ui.lab.LabBottomSheet
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.lab.LabLineChip
import com.yenreh.caligo.ui.lab.LabOutlinedButton
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
    /** Save the stop under the label given, empty for none */
    val save: suspend (customName: String) -> FavoriteSave,
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
 * [onGoHere], when given, is "Go here": the sheet closes and the trip
 * planner opens with the stop as where to go.
 */
@Composable
fun StopDetailsSheet(
    stop: NearbyStop,
    isSaved: Boolean,
    actions: StopDetailsActions,
    onDismiss: () -> Unit,
    fetchOnOpen: Boolean = false,
    onGoHere: ((TripPlace) -> Unit)? = null,
    anchorLatitude: Double = 0.0,
    anchorLongitude: Double = 0.0,
) {
    LabBottomSheet(onDismiss = onDismiss, dragHandle = true) {
        val lines = rememberLoader(stop.id) { actions.loadLines(stop.id) }
        StopDetailsContent(
            stop,
            isSaved,
            lines.state,
            actions,
            onDismiss,
            fetchOnOpen,
            onGoHere?.let { go -> { place -> onDismiss(); go(place) } },
            anchorLatitude,
            anchorLongitude,
        )
    }
}

/**
 * The sheet's content, apart so it shows without one. [onGoHere] takes the
 * stop as a place to go: where it stands when placed, otherwise where it
 * was found from, [anchorLatitude] and [anchorLongitude], a few metres off.
 */
@Composable
fun StopDetailsContent(
    initial: NearbyStop,
    isSaved: Boolean,
    lines: Loaded<List<StopLine>>,
    actions: StopDetailsActions,
    onDismiss: () -> Unit,
    fetchOnOpen: Boolean = false,
    onGoHere: ((TripPlace) -> Unit)? = null,
    anchorLatitude: Double = 0.0,
    anchorLongitude: Double = 0.0,
) {
    val p = LabTheme.palette
    val scope = rememberCoroutineScope()
    var stop by remember { mutableStateOf(initial) }
    val now = LocalNow.current
    // The buses it opens with count as listed when it opens
    var fading by remember { mutableStateOf(FadingArrivals.EMPTY.fed(initial.id, initial.arrivals, now())) }
    // When the arrivals on screen were fetched; null until a refresh, since
    // the list the sheet came from does not say
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(fetchOnOpen) }
    var failed by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    // The favorite already holding this stop's id, by name, once a save met it
    var takenBy by rememberSaveable { mutableStateOf<String?>(null) }

    /** The disk failed to save it */
    var saveFailed by rememberSaveable { mutableStateOf(false) }

    suspend fun refresh() {
        refreshing = true
        try {
            val fresh = actions.refresh(stop)
            val at = now()
            stop = fresh
            fading = fading.fed(fresh.id, fresh.arrivals, at)
            updatedAt = at
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
                else -> SheetArrivals(fading) { row ->
                    AwaitedBus(
                        stopId = stop.id,
                        stopName = stop.name,
                        bus = row.bus,
                        anchor = GeoPoint(anchorLatitude, anchorLongitude),
                        place = stop.latitude?.let { lat -> stop.longitude?.let { GeoPoint(lat, it) } },
                    )
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
            if (onGoHere != null) {
                LabOutlinedButton(
                    onClick = {
                        onGoHere(
                            TripPlace(
                                stop.latitude ?: anchorLatitude,
                                stop.longitude ?: anchorLongitude,
                                stop.name,
                                kind = PlaceKind.STOP,
                                stopId = stop.id,
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("go_here"),
                ) {
                    Icon(painterResource(R.drawable.ic_directions), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.go_here))
                }
                Spacer(Modifier.height(8.dp))
            }
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
            if (saveFailed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.could_not_save_stop),
                    style = LabText.statusLine(p).copy(color = p.crit),
                    modifier = Modifier.testTag("save_failed"),
                )
            }
            takenBy?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.stop_id_taken, it),
                    style = LabText.statusLine(p).copy(color = p.crit),
                    modifier = Modifier.testTag("stop_id_taken"),
                )
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
                takenBy = null
                saveFailed = false
                // Closed once saved; a sheet already closed stays so
                scope.launch {
                    when (val saved = actions.save(customName)) {
                        FavoriteSave.Saved -> onDismiss()
                        is FavoriteSave.Taken -> takenBy = saved.favorite.displayName
                        // The Flutter app said nothing
                        FavoriteSave.Failed -> saveFailed = true
                    }
                }
            },
        )
    }
}

/**
 * The stop's rows as they read now. Alone on the live clock: each beat
 * works the rows out again, with what has reached the stop or passed.
 */
@Composable
private fun SheetArrivals(fading: FadingArrivals, awaited: (FadingRow) -> AwaitedBus) {
    watchLiveClock()
    val rows = fading.rows(LocalNow.current()).shown(SHEET_ROWS)
    if (rows.none { it.phase !is ArrivalPhase.Passed }) {
        Text(
            stringResource(R.string.no_buses_coming),
            modifier = Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = LabTheme.palette.muted),
        )
        if (rows.isEmpty()) return
        LabDivider()
    }
    Column {
        rows.forEachIndexed { i, row ->
            if (i > 0) LabDivider()
            ArrivalRow(
                row.bus,
                phase = row.phase,
                padding = PaddingValues(vertical = 8.dp),
                bell = { RowBell(row, awaited(row), fading) },
            )
        }
    }
}

/** Rows the sheet lists, besides those passed */
private const val SHEET_ROWS = 6

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
 * whether it is saved already, its fresh arrivals and its lines.
 * [fetchOnOpen] and [onGoHere] as the sheet's.
 */
@Composable
fun StopDetailsFor(
    open: OpenStop,
    viewModel: StopsViewModel,
    onDismiss: () -> Unit,
    fetchOnOpen: Boolean = false,
    onGoHere: ((TripPlace) -> Unit)? = null,
) {
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
        fetchOnOpen = fetchOnOpen,
        onGoHere = onGoHere,
        anchorLatitude = open.anchorLatitude,
        anchorLongitude = open.anchorLongitude,
    )
}
