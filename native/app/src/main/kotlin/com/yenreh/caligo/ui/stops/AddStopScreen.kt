package com.yenreh.caligo.ui.stops

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.data.location.LocationUnavailableException
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.rememberLoader
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.launch

/**
 * The tabs of the add-stop screen. The Flutter screen opens on a map and
 * has the lines between these two: those come with the map and the line
 * screens.
 */
private enum class AddStopTab(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    STATIONS(R.drawable.ic_directions_bus, R.string.station_catalog),
    NEARBY(R.drawable.ic_near_me, R.string.nearby_stops),
}

/**
 * Look stops up in the station catalog or around the device, and save
 * the ones worth following. [explore] is the home's search: only the
 * title changes, saving stays one tap away.
 */
@Composable
fun AddStopScreen(
    viewModel: StopsViewModel,
    onBack: () -> Unit,
    onStation: (Station) -> Unit,
    modifier: Modifier = Modifier,
    explore: Boolean = false,
    initialTab: Int = 0,
) {
    val p = LabTheme.palette
    val tabs = AddStopTab.entries
    val pager = rememberPagerState(initialPage = initialTab) { tabs.size }
    // Kept while the tabs change, as the Flutter screen's controller
    val search = rememberTextFieldState()
    var details by remember { mutableStateOf<OpenStop?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LabTopBar(
                title = stringResource(if (explore) R.string.find_stops else R.string.add_stop),
                onBack = onBack,
                bottom = { Tabs(pager, tabs) },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
            key = { tabs[it] },
        ) { page ->
            when (tabs[page]) {
                AddStopTab.STATIONS -> StationsTab(viewModel, search, onStation)
                AddStopTab.NEARBY -> NearbyTab(viewModel, onOpen = { details = it })
            }
        }
    }

    details?.let { open -> StopDetailsFor(open, viewModel, onDismiss = { details = null }) }
}

/**
 * Tabs with the icon above the label, as Flutter's stacked tabs: the
 * chosen one in ink with an accent underline, which the bar's rule then
 * paints over, as in the Flutter app
 */
@Composable
private fun Tabs(pager: PagerState, tabs: List<AddStopTab>) {
    val p = LabTheme.palette
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .drawBehind {
                val width = size.width / tabs.size
                val at = (pager.currentPage + pager.currentPageOffsetFraction) * width
                val weight = 2.dp.toPx()
                drawRect(p.accent, Offset(at, size.height - weight), Size(width, weight))
            },
    ) {
        tabs.forEachIndexed { i, tab ->
            val selected = pager.currentPage == i
            val color = if (selected) p.ink else p.muted
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = selected, role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } }
                    .padding(horizontal = 4.dp)
                    .testTag("tab_${tab.name.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(tab.icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(tab.label),
                    style = MaterialTheme.typography.labelLarge.copy(color = color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Searchable MIO station catalog, no location permission needed */
@Composable
private fun StationsTab(viewModel: StopsViewModel, query: TextFieldState, onStation: (Station) -> Unit) {
    val p = LabTheme.palette
    LaunchedEffect(viewModel) { viewModel.watchStations() }
    val stations by viewModel.stations.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        LabTextField(
            state = query,
            style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .testTag("station_search"),
            hint = stringResource(R.string.search_station),
            leadingIcon = painterResource(R.drawable.ic_search),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (val state = stations) {
                Loaded.Loading -> Spinner()
                is Loaded.Failed -> Message(
                    icon = R.drawable.ic_cloud_off,
                    text = stringResource(R.string.could_not_load_arrivals),
                    onRetry = viewModel::reloadStations,
                )
                is Loaded.Ready -> {
                    val needle = query.text.toString().trim().lowercase()
                    val shown = if (needle.isEmpty()) {
                        state.value
                    } else {
                        state.value.filter { needle in it.name.lowercase() || needle in it.address.lowercase() }
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("stations")) {
                        itemsIndexed(shown) { i, station ->
                            if (i > 0) LabDivider()
                            LabListTile(
                                title = station.name,
                                subtitle = station.address,
                                leading = painterResource(R.drawable.ic_directions_bus),
                                modifier = Modifier.clickable { onStation(station) },
                                trailing = {
                                    Icon(
                                        painterResource(R.drawable.ic_star_outline),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = p.muted,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stops around the device, with the buses coming to each. Asked for each
 * time the tab shows, as the Flutter provider was, the permission prompt
 * first when it is still to give.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyTab(viewModel: StopsViewModel, onOpen: (OpenStop) -> Unit) {
    val p = LabTheme.palette
    val nearby = rememberLoader(viewModel) { viewModel.nearbyStops() }

    when (val state = nearby.state) {
        Loaded.Loading -> Spinner()
        is Loaded.Failed -> {
            val error = state.error
            Message(
                icon = R.drawable.ic_location_off,
                text = stringResource(
                    when {
                        error !is LocationUnavailableException -> R.string.could_not_load_arrivals
                        error.permanentlyDenied -> R.string.location_denied_forever
                        else -> R.string.location_unavailable
                    },
                ),
                onRetry = nearby::reload,
            )
        }
        is Loaded.Ready if state.value.stops.isEmpty() -> Message(
            icon = R.drawable.ic_signpost,
            text = stringResource(R.string.no_stops_nearby),
            onRetry = nearby::reload,
        )
        is Loaded.Ready -> {
            val result = state.value
            val pull = rememberPullToRefreshState()
            val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
                .calculateBottomPadding()
            // Asked again in the background: the spinner goes at once, the
            // list stays until the new one comes
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = nearby::reload,
                modifier = Modifier.fillMaxSize(),
                state = pull,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pull,
                        isRefreshing = false,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = p.paper,
                        color = p.accent,
                    )
                },
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("nearby_stops"),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + bottom),
                ) {
                    itemsIndexed(result.stops) { i, stop ->
                        if (i > 0) LabDivider()
                        NearbyStopRow(stop, onClick = { onOpen(OpenStop(stop, result.latitude, result.longitude)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(36.dp), color = LabTheme.palette.accent, strokeWidth = 4.dp)
    }
}

/** Something went wrong or nothing is there, with a retry */
@Composable
private fun Message(@DrawableRes icon: Int, text: String, onRetry: () -> Unit) {
    LabEmptyState(
        icon = painterResource(icon),
        message = text,
        actionLabel = stringResource(R.string.retry),
        onAction = onRetry,
        secondaryAction = true,
        modifier = Modifier.testTag("add_stop_message"),
    )
}
