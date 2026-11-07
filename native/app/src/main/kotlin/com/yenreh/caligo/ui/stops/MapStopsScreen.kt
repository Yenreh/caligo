package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.labBottomInset
import com.yenreh.caligo.ui.lab.labSideInsets
import com.yenreh.caligo.ui.map.CompassNeedle
import com.yenreh.caligo.ui.map.HaloIcon
import com.yenreh.caligo.ui.map.LabMap
import com.yenreh.caligo.ui.map.LabMapMarker
import com.yenreh.caligo.ui.map.MapButton
import com.yenreh.caligo.ui.map.MapCredits
import com.yenreh.caligo.ui.map.MapStopMarker
import com.yenreh.caligo.ui.map.MapTileSource
import com.yenreh.caligo.ui.map.SearchGlyph
import com.yenreh.caligo.ui.map.tilesPalette
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The map tab of the add-stop screen, with [tiles] as the [settings] ask
 * and a view model of its own; [onOpen] shows a stop's details
 */
@Composable
fun MapStopsRoute(
    settings: Settings,
    tiles: MapTileSource,
    onOpen: (OpenStop) -> Unit,
    viewModel: MapStopsViewModel = viewModel(factory = MapStopsViewModel.Factory),
) {
    MapStopsScreen(viewModel, tiles, darkTiles = settings.darkMap, carto = tiles.byCarto, onOpen = onOpen)
}

/**
 * Pick any place on the map and inspect the stops around it: the
 * crosshair marks the point a search looks around, the list below shows
 * what it finds, and each stop placed on the map has a marker. Tapping a
 * marker or a row brings the map to the stop and opens its details.
 *
 * [darkTiles] says the tiles are dark, which the controls on them follow;
 * [carto] that they are CARTO's, which the credits then name.
 */
@Composable
fun MapStopsScreen(
    viewModel: MapStopsViewModel,
    tiles: MapTileSource,
    darkTiles: Boolean,
    carto: Boolean,
    onOpen: (OpenStop) -> Unit,
    modifier: Modifier = Modifier,
    /** Tiles fade in as they come; screenshots turn it off */
    fadeIn: Boolean = true,
) {
    val p = LabTheme.palette
    val state by viewModel.screen.collectAsStateWithLifecycle()
    val tilesPalette = tilesPalette(dark = darkTiles)
    var creditsOpen by remember { mutableStateOf(false) }
    val map = state.map
    val searched = state.searched

    fun openDetails(stop: NearbyStop) {
        val point = searched ?: return
        viewModel.focus(stop)
        onOpen(OpenStop(stop, point.latitude, point.longitude))
    }

    // Saved on leaving, as the Flutter screen did once disposed
    DisposableEffect(viewModel) { onDispose { viewModel.saveCamera() } }

    if (map != null) {
        // Moving the map puts the credits away, as tapping elsewhere would
        LaunchedEffect(map) {
            snapshotFlow { map.camera.center to map.camera.zoom }
                .distinctUntilChanged()
                .drop(1)
                .collect { creditsOpen = false }
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(3f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // Plain paper for the moment it takes to pick where to open, so
            // no tiles are fetched for a place the map leaves at once
            if (map == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(tilesPalette.paper),
                )
            } else {
                val stops = (state.results as? Loaded.Ready)?.value.orEmpty().filter { it.hasPosition }
                val byId = stops.associateBy { it.id }
                val selected = state.selectedStopId
                LabMap(
                    state = map,
                    tiles = tiles,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("map"),
                    markers = remember(stops) {
                        stops.map {
                            LabMapMarker(it.id, GeoPoint(it.latitude!!, it.longitude!!))
                        }
                    },
                    markerContent = { id ->
                        MapStopMarker(selected = id == selected, palette = p, modifier = Modifier.testTag("marker_$id"))
                    },
                    onMarkerTap = { id -> byId[id]?.let(::openDetails) },
                    fadeIn = fadeIn,
                )
            }

            // Crosshair marking the point that will be searched
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = p.crit,
            )

            // The data is OpenStreetMap's (ODbL) and the tiles are drawn by
            // CARTO or the OSM Foundation: all ask to be credited on the map.
            // Kept behind a button aligned with the others.
            MapCredits(
                open = creditsOpen,
                onToggle = { creditsOpen = !creditsOpen },
                tiles = tilesPalette,
                carto = carto,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp),
            )

            if (map != null) {
                val rotation = map.camera.rotation
                if (turned(rotation)) {
                    MapButton(
                        onClick = { map.rotate(0f) },
                        tooltip = stringResource(R.string.reset_north),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 12.dp),
                    ) {
                        CompassNeedle(rotationDegrees = rotation, tiles = tilesPalette)
                    }
                }
            }

            // Icon only and centred, so it hides as little map as possible
            MapButton(
                onClick = viewModel::searchHere,
                tooltip = stringResource(R.string.search_here),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                SearchGlyph(tilesPalette)
            }

            MapButton(
                onClick = if (state.locating) null else ({ viewModel.centreOnDevice() }),
                tooltip = stringResource(R.string.my_location),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            ) {
                if (state.locating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("locating"),
                        color = tilesPalette.ink,
                        strokeWidth = 2.dp,
                    )
                } else {
                    HaloIcon(
                        painterResource(R.drawable.ic_my_location),
                        tint = tilesPalette.ink,
                        halo = tilesPalette.paper,
                        size = 24.dp,
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(p.ink),
        )

        Box(
            Modifier
                .weight(2f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (val results = state.results) {
                null -> Text(
                    text = stringResource(R.string.map_hint),
                    style = LabText.statusLine(p),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                Loaded.Loading -> CircularProgressIndicator(
                    Modifier.size(36.dp),
                    color = p.accent,
                    strokeWidth = 4.dp,
                )
                is Loaded.Failed -> Message(stringResource(R.string.could_not_load_arrivals))
                is Loaded.Ready if results.value.isEmpty() -> Message(stringResource(R.string.no_stops_nearby))
                is Loaded.Ready -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(labSideInsets())
                        .testTag("map_stops"),
                    contentPadding = PaddingValues(bottom = labBottomInset()),
                ) {
                    itemsIndexed(results.value) { i, stop ->
                        if (i > 0) LabDivider()
                        // Same as tapping its marker: bring the map to it
                        // before showing the details
                        NearbyStopRow(stop, selected = stop.id == state.selectedStopId, onClick = { openDetails(stop) })
                    }
                }
            }
        }
    }
}

/** Turned more than half a degree either way from north */
private fun turned(rotation: Float): Boolean {
    val angle = ((rotation % 360) + 360) % 360
    return minOf(angle, 360 - angle) > 0.5f
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = LabTheme.palette.ink))
}
