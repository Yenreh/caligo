package com.yenreh.caligo.ui.trips

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.map.LabMap
import com.yenreh.caligo.ui.map.LabMapState
import com.yenreh.caligo.ui.map.MapCamera
import com.yenreh.caligo.ui.map.MapCredits
import com.yenreh.caligo.ui.map.MapTileSource
import com.yenreh.caligo.ui.map.tilesPalette
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * A map with a crosshair: the point under it is the one chosen, handed
 * to [onUse]. Opens on [near] close up, or on Cali's centre; north up, as
 * the Flutter picker's. Where the map was left is saved with the screen.
 */
@Composable
fun MapPointPicker(
    near: TripPlace?,
    tiles: MapTileSource,
    darkTiles: Boolean,
    carto: Boolean,
    onBack: () -> Unit,
    onUse: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    /** Tiles fade in as they come; screenshots turn it off */
    fadeIn: Boolean = true,
) {
    val p = LabTheme.palette
    val tilesPalette = tilesPalette(dark = darkTiles)
    val map = rememberSaveable(saver = PointMapSaver) {
        val camera = if (near == null) {
            MapCamera(CALI_CENTER, CENTER_ZOOM)
        } else {
            MapCamera(GeoPoint(near.latitude, near.longitude), NEAR_ZOOM)
        }
        pointMap(camera)
    }
    var creditsOpen by remember { mutableStateOf(false) }

    // Moving the map puts the credits away, as tapping elsewhere would
    LaunchedEffect(map) {
        snapshotFlow { map.camera.center to map.camera.zoom }
            .distinctUntilChanged()
            .drop(1)
            .collect { creditsOpen = false }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = { LabTopBar(title = stringResource(R.string.pick_on_map), onBack = onBack) },
    ) { padding ->
        // The map runs under the system's navigation buttons; the controls stay clear of them
        val inset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LabMap(
                state = map,
                tiles = tiles,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("map"),
                fadeIn = fadeIn,
            )
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = p.crit,
            )
            MapCredits(
                open = creditsOpen,
                onToggle = { creditsOpen = !creditsOpen },
                tiles = tilesPalette,
                carto = carto,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp + inset),
            )
            LabFilledButton(
                onClick = { onUse(map.camera.center) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 72.dp, end = 72.dp, bottom = 16.dp + inset)
                    .fillMaxWidth()
                    .testTag("use_point"),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.use_point))
            }
        }
    }
}

/** Where the Flutter picker opens without a place to start from */
private val CALI_CENTER = GeoPoint(3.4516, -76.5320)
private const val CENTER_ZOOM = 13.0
private const val NEAR_ZOOM = 16.0

private fun pointMap(camera: MapCamera) = LabMapState(camera, minZoom = 11.0, maxZoom = 18.0, rotatable = false)

/** The camera in plain values, as a saved state holds them */
private val PointMapSaver: Saver<LabMapState, Any> = listSaver(
    save = { listOf(it.camera.center.latitude, it.camera.center.longitude, it.camera.zoom) },
    restore = { pointMap(MapCamera(GeoPoint(it[0], it[1]), it[2])) },
)
