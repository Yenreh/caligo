package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.ui.common.LiveClock
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.rememberLoader
import com.yenreh.caligo.ui.lab.LabBottomSheet
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabFloatingActionButton
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabReorderableList
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTone
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.labBottomInset
import com.yenreh.caligo.ui.lab.labSideInsets
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.theme.LabTheme
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/** What the stops screen's buttons, gestures and sheets do */
class StopsActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    /** Every stop's arrivals, at once */
    val onRefresh: () -> Unit = {},
    /** The same, pulled down: the spinner shows until it ends */
    val onPullRefresh: suspend () -> Unit = {},
    val onRemove: (id: String) -> Unit = {},
    val onToggleHome: (id: String, show: Boolean) -> Unit = { _, _ -> },
    val onEdit: (id: String, edit: FavoriteEdit) -> Unit = { _, _ -> },
    /** Point a missing favorite at the stop picked in its place */
    val onRelink: (id: String, stop: NearbyStop) -> Unit = { _, _ -> },
    /** With Flutter's indexes, as [StopsViewModel.reorderFavorites] takes them */
    val onReorder: (oldIndex: Int, newIndex: Int) -> Unit = { _, _ -> },
    val loadLines: suspend (stopId: String) -> List<StopLine> = { emptyList() },
    val nearbyAt: suspend (latitude: Double, longitude: Double) -> List<NearbyStop> = { _, _ -> emptyList() },
)

/**
 * The stops screen with its data: the activity's [StopsViewModel], which
 * the home shares. Arrivals on their pace while it shows, every stop, and
 * a failed refresh in a snackbar.
 */
@Composable
fun StopsRoute(
    settings: Settings,
    viewModel: StopsViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    val snackbars = rememberLabSnackbars()

    WatchArrivals(viewModel)
    ListenArrivalErrors(viewModel, snackbars)

    StopsScreen(
        stops = stops,
        sortedByProximity = settings.sortStopsByProximity,
        modifier = modifier,
        snackbars = snackbars,
        actions = StopsActions(
            onBack = onBack,
            onAdd = onAdd,
            onRefresh = { viewModel.refreshArrivals(force = true) },
            onPullRefresh = { viewModel.refreshArrivals(force = true).join() },
            onRemove = { viewModel.removeFavorite(it) },
            onToggleHome = { id, show -> viewModel.setShowOnHome(id, show) },
            onEdit = { id, edit -> viewModel.editFavorite(id, customName = edit.name, lines = edit.lines) },
            onRelink = { id, stop -> viewModel.relinkFavorite(id, stop) },
            onReorder = { from, to -> viewModel.reorderFavorites(from, to) },
            loadLines = viewModel::linesByStop,
            nearbyAt = viewModel::nearbyAt,
        ),
    )
}

/**
 * A failed refresh, as the stops screen tells it, with a retry. Only
 * changes count, as with Riverpod's ref.listen: an error the screen finds
 * on opening is not told.
 */
@Composable
private fun ListenArrivalErrors(viewModel: StopsViewModel, snackbars: LabSnackbars) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbars, resources) {
        var previous: StopsState? = null
        viewModel.stops.collect { next ->
            val before = previous
            previous = next
            if (before == null) return@collect
            val error = next.error
            if (error != null && error !== before.error) {
                snackbars.show(
                    resources.getString(R.string.could_not_load_arrivals),
                    LabTone.CRIT,
                    duration = 3.seconds,
                    actionLabel = resources.getString(R.string.retry),
                    onAction = { viewModel.refreshArrivals(force = true) },
                )
            }
        }
    }
}

/**
 * Dashboard of favorite stops with their upcoming buses: each one can be
 * removed, renamed with the lines it shows, kept off the home, linked to
 * another stop once it goes missing, and, in the saved order, held and
 * dragged to a new place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopsScreen(
    stops: StopsState,
    sortedByProximity: Boolean,
    modifier: Modifier = Modifier,
    actions: StopsActions = StopsActions(),
    snackbars: LabSnackbars = rememberLabSnackbars(),
) {
    val p = LabTheme.palette
    val scope = rememberCoroutineScope()
    // The favorites the sheets are about, by id
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var relinking by rememberSaveable { mutableStateOf<String?>(null) }
    var pulling by rememberSaveable { mutableStateOf(false) }

    LiveClock {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = p.bg,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                LabTopBar(stringResource(R.string.favorite_stops), onBack = actions.onBack) {
                    RefreshAction(stops.isRefreshing, actions.onRefresh)
                }
            },
            floatingActionButton = {
                LabFloatingActionButton(
                    icon = painterResource(R.drawable.ic_add_location_alt),
                    contentDescription = stringResource(R.string.add_stop),
                    onClick = actions.onAdd,
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
                )
            },
            // Above the button, as Flutter's Scaffold puts a floating snackbar,
        // the button below it already clear of the system bars
        snackbarHost = {
            LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)))
        },
        ) { padding ->
            val body = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
            when {
                stops.isLoading -> Box(body, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = p.accent, strokeWidth = 4.dp)
                }
                stops.favorites.isEmpty() -> LabEmptyState(
                    icon = painterResource(R.drawable.ic_signpost),
                    title = stringResource(R.string.no_favorite_stops),
                    message = stringResource(R.string.no_favorite_stops_message),
                    modifier = body.testTag("stops_empty"),
                )
                else -> {
                    val pull = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = pulling,
                        onRefresh = {
                            pulling = true
                            scope.launch {
                                try {
                                    actions.onPullRefresh()
                                } finally {
                                    pulling = false
                                }
                            }
                        },
                        modifier = body,
                        state = pull,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pull,
                                isRefreshing = pulling,
                                modifier = Modifier.align(Alignment.TopCenter),
                                containerColor = p.paper,
                                color = p.accent,
                            )
                        },
                    ) {
                        FavoritesList(stops, sortedByProximity, actions, onEdit = { editing = it }, onRelink = {
                            relinking = it
                        })
                    }
                }
            }
        }
    }

    stops.favorites.firstOrNull { it.id == editing }?.let { stop ->
        EditFavoriteSheet(
            stop = stop,
            seenLines = stops.arrivals[stop.id].orEmpty().map { it.line },
            loadLines = actions.loadLines,
            onDismiss = { editing = null },
            onSave = { edit ->
                editing = null
                actions.onEdit(stop.id, edit)
            },
        )
    }

    stops.favorites.firstOrNull { it.id == relinking }?.let { stop ->
        RelinkSheet(
            stop = stop,
            nearbyAt = actions.nearbyAt,
            onDismiss = { relinking = null },
            onChosen = { chosen ->
                relinking = null
                actions.onRelink(stop.id, chosen)
            },
        )
    }
}

@Composable
private fun FavoritesList(
    stops: StopsState,
    sortedByProximity: Boolean,
    actions: StopsActions,
    onEdit: (String) -> Unit,
    onRelink: (String) -> Unit,
) {
    val bottom = labBottomInset()
    LabReorderableList(
        items = stops.favorites,
        key = { it.id },
        onReorder = actions.onReorder,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(labSideInsets())
            .testTag("stops_list"),
        // Room at the end for the button floating over the list
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp + bottom),
        // In the saved order a stop can be held and dragged; sorted by
        // proximity, the distance decides instead
        dragEnabled = !sortedByProximity,
    ) { stop: FavoriteStop ->
        FavoriteStopCard(
            stop = stop,
            arrivals = stops.arrivals[stop.id],
            isLoading = stops.isRefreshing,
            modifier = Modifier.padding(bottom = 12.dp),
            onRemove = { actions.onRemove(stop.id) },
            onRename = { onEdit(stop.id) },
            onToggleHome = { actions.onToggleHome(stop.id, !stop.showOnHome) },
            onRelink = if (stop.looksGone) ({ onRelink(stop.id) }) else null,
        )
    }
}

/** A spinner while the arrivals refresh, else the button that starts it */
@Composable
private fun RefreshAction(isRefreshing: Boolean, onRefresh: () -> Unit) {
    val p = LabTheme.palette
    if (isRefreshing) {
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .size(18.dp)
                .testTag("refreshing_stops"),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
        }
    } else {
        val label = stringResource(R.string.retry)
        LabTooltip(label) {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("refresh_stops"),
                shape = RectangleShape,
            ) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = label, tint = p.ink)
            }
        }
    }
}

/** Let the user pick the stop that replaced a missing favorite, among those around its place */
@Composable
private fun RelinkSheet(
    stop: FavoriteStop,
    nearbyAt: suspend (Double, Double) -> List<NearbyStop>,
    onDismiss: () -> Unit,
    onChosen: (NearbyStop) -> Unit,
) {
    LabBottomSheet(onDismiss = onDismiss, dragHandle = true) {
        val nearby = rememberLoader(stop.id) { nearbyAt(stop.anchorLatitude, stop.anchorLongitude) }
        val message = Modifier.padding(32.dp)
        when (val state = nearby.state) {
            Loaded.Loading -> Box(message.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(36.dp), color = LabTheme.palette.accent, strokeWidth = 4.dp)
            }
            is Loaded.Failed -> SheetMessage(stringResource(R.string.could_not_load_arrivals), message)
            is Loaded.Ready if state.value.isEmpty() -> SheetMessage(stringResource(R.string.no_stops_nearby), message)
            is Loaded.Ready -> Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag("relink_choices"),
            ) {
                for (candidate in state.value) {
                    LabDivider()
                    LabListTile(
                        title = candidate.name,
                        subtitle = metersAway(candidate),
                        leading = painterResource(R.drawable.ic_signpost),
                        modifier = Modifier.clickable { onChosen(candidate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetMessage(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium.copy(color = LabTheme.palette.ink))
}
