package com.yenreh.caligo.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.yenreh.caligo.domain.GeoPoint
import java.io.ByteArrayInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.addPath
import ovh.plrapps.mapcompose.api.centroidX
import ovh.plrapps.mapcompose.api.centroidY
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.hasMarker
import ovh.plrapps.mapcompose.api.hasPath
import ovh.plrapps.mapcompose.api.makePathDataBuilder
import ovh.plrapps.mapcompose.api.moveMarker
import ovh.plrapps.mapcompose.api.onMarkerClick
import ovh.plrapps.mapcompose.api.onMarkerLongPress
import ovh.plrapps.mapcompose.api.removeAllLayers
import ovh.plrapps.mapcompose.api.removeMarker
import ovh.plrapps.mapcompose.api.removePath
import ovh.plrapps.mapcompose.api.rotation
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.setColorFilterProvider
import ovh.plrapps.mapcompose.api.setFadeInSpeed
import ovh.plrapps.mapcompose.api.setMapBackground
import ovh.plrapps.mapcompose.api.setStateChangeListener
import ovh.plrapps.mapcompose.api.snapScrollTo
import ovh.plrapps.mapcompose.api.updateMarkerZ
import ovh.plrapps.mapcompose.api.updatePath
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.MapUI
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.paths.model.PatternItem
import ovh.plrapps.mapcompose.ui.state.MapState

/**
 * A raster map of web map tiles, with markers and lines on it: pan, pinch
 * to zoom, fling, double tap to zoom in, two fingers to turn.
 *
 * The app's own map: screens use this and [LabMapState], never the
 * library under it (MapCompose, which draws the tiles with Compose's own
 * canvas), so another one would only change this file.
 *
 * [markerContent] draws each of [markers], centred on its position;
 * tapping one calls [onMarkerTap] with its id, holding it
 * [onMarkerLongPress]. [polylines] go under the markers.
 */
@Composable
fun LabMap(
    state: LabMapState,
    tiles: MapTileSource,
    modifier: Modifier = Modifier,
    markers: List<LabMapMarker> = emptyList(),
    markerContent: @Composable (id: String) -> Unit = {},
    onMarkerTap: (id: String) -> Unit = {},
    onMarkerLongPress: (id: String) -> Unit = {},
    polylines: List<LabMapPolyline> = emptyList(),
    /** Tiles fade in as they come, as flutter_map's; screenshots turn it off */
    fadeIn: Boolean = true,
) {
    val screen = LocalDensity.current
    val density = screen.density
    val geometry = remember(density, tiles.tilePixels) { MapGeometry(density, tiles.tilePixels) }
    val scope = rememberCoroutineScope()
    val content by rememberUpdatedState(markerContent)
    val tap by rememberUpdatedState(onMarkerTap)
    val hold by rememberUpdatedState(onMarkerLongPress)

    // A new geometry is a new map, where the last one was left
    val map = remember(geometry, state) { geometry.mapFor(state) }
    // What it shows, to take away what is no longer asked for
    val shownMarkers = remember(map) { mutableSetOf<String>() }
    val shownLines = remember(map) { mutableSetOf<String>() }

    DisposableEffect(map) {
        val driver = geometry.driverFor(map, scope) { state.follow(geometry.cameraOf(map)) }
        map.setStateChangeListener { if (!driver.moving) state.follow(geometry.cameraOf(this)) }
        map.onMarkerClick { id, _, _ -> tap(id) }
        map.onMarkerLongPress { id, _, _ -> hold(id) }
        state.driver = driver
        onDispose {
            state.driver = null
            map.shutdown()
        }
    }

    LaunchedEffect(map, fadeIn) { map.setFadeInSpeed(if (fadeIn) FADE_IN_SPEED else 1f) }

    LaunchedEffect(map, tiles) {
        map.removeAllLayers()
        map.setColorFilterProvider { _, _, _ -> if (tiles.inverted) INVERTED else null }
        map.addLayer(tiles.streamProvider())
    }

    LaunchedEffect(map, markers) {
        val ids = markers.map { it.id }.toSet()
        for (id in shownMarkers - ids) map.removeMarker(id)
        shownMarkers.retainAll(ids)
        for (marker in markers) {
            val x = WebMercator.x(marker.position.longitude)
            val y = WebMercator.y(marker.position.latitude)
            if (map.hasMarker(marker.id)) {
                map.moveMarker(marker.id, x, y)
                map.updateMarkerZ(marker.id, marker.zIndex)
            } else {
                map.addMarker(marker.id, x, y, relativeOffset = Offset(-0.5f, -0.5f), zIndex = marker.zIndex) {
                    content(marker.id)
                }
                shownMarkers.add(marker.id)
            }
        }
    }

    LaunchedEffect(map, polylines) {
        // A border is a wider line of its own, under every line
        val paths = polylines.flatMap { line ->
            // A dot, then a gap of half its width: flutter_map's spacing of one and a half widths
            val pattern = if (line.dotted) listOf(PatternItem.Dot, PatternItem.Gap(line.width / 2)) else null
            listOfNotNull(
                line.borderColor?.let {
                    MapPath("${line.id}$BORDER", line.points, it, line.width + line.borderWidth, 0f, pattern)
                },
                MapPath(line.id, line.points, line.color, line.width, 1f, pattern),
            )
        }
        val ids = paths.map { it.id }.toSet()
        for (id in shownLines - ids) map.removePath(id)
        shownLines.retainAll(ids)
        for (path in paths) {
            val data = map.makePathDataBuilder().apply {
                for (point in path.points) addPoint(WebMercator.x(point.longitude), WebMercator.y(point.latitude))
            }.build()
            when {
                // Fewer than two points: nothing to draw
                data == null -> {
                    if (map.hasPath(path.id)) map.removePath(path.id)
                    shownLines.remove(path.id)
                }
                map.hasPath(path.id) -> map.updatePath(
                    path.id,
                    pathData = data,
                    color = path.color,
                    width = path.width,
                    pattern = path.pattern,
                )
                else -> {
                    map.addPath(
                        path.id,
                        data,
                        width = path.width,
                        color = path.color,
                        zIndex = path.zIndex,
                        pattern = path.pattern,
                    )
                    shownLines.add(path.id)
                }
            }
        }
    }

    MapUI(
        modifier.onSizeChanged { size ->
            state.viewport = with(screen) { DpSize(size.width.toDp(), size.height.toDp()) }
        },
        state = map,
    )
}

/** One path as MapCompose draws it: a line, or the border under one */
private class MapPath(
    val id: String,
    val points: List<GeoPoint>,
    val color: Color,
    val width: Dp,
    val zIndex: Float,
    val pattern: List<PatternItem>?,
)

/** Added to a line's id for the path of its border */
private const val BORDER = "#border"

/** About flutter_map's 100 ms fade at 60 frames a second */
private const val FADE_IN_SPEED = 0.17f

/** flutter_map's grey under the tiles while they load */
private val MAP_BACKGROUND = Color(0xFFE0E0E0)

/** flutter_map's darkModeTileBuilder: colours inverted, then hues turned half a circle */
private val INVERTED = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.43f, -0.144f, 0f, 255f,
            -0.426f, -0.43f, -0.144f, 0f, 255f,
            -0.426f, -1.43f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Tiles for MapCompose, which reads them on its own threads and must never see one fail */
private fun MapTileSource.streamProvider() = TileStreamProvider { row, col, zoom ->
    try {
        tile(zoom, col, row)?.let(::ByteArrayInputStream)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
}

/**
 * How web map zooms land on MapCompose's scales.
 *
 * MapCompose sizes the world in pixels at scale 1 and picks, for each
 * scale, the level whose tiles are drawn no smaller than they are. A web
 * map picks the zoom nearest the camera's, flutter_map's round(zoom),
 * whose tiles are drawn at 256 dp, larger than they are on a dense
 * screen. Declaring each tile [tileSize] pixels, 256 dp times the square
 * root of two, makes the level MapCompose picks the rounded zoom. Tiles
 * are drawn to fit whatever their own pixels, and never fewer than
 * [tilePixels] are declared, as MapCompose decodes them into a bitmap of
 * that size where hardware bitmaps are not used.
 */
internal class MapGeometry(private val density: Float, tilePixels: Int) {
    val tileSize = max((WEB_TILE * density * sqrt(2.0)).roundToInt(), tilePixels)
    private val fullSize = tileSize * (1 shl MAX_LEVEL)

    fun scaleFor(zoom: Double): Double = WEB_TILE * density * 2.0.pow(zoom) / fullSize

    fun zoomFor(scale: Double): Double = ln(scale * fullSize / (WEB_TILE * density)) / ln(2.0)

    fun mapFor(state: LabMapState): MapState {
        val camera = state.camera
        return MapState(
            levelCount = MAX_LEVEL + 1,
            fullWidth = fullSize,
            fullHeight = fullSize,
            tileSize = tileSize,
            workerCount = WORKERS,
        ) {
            scroll(WebMercator.x(camera.center.longitude), WebMercator.y(camera.center.latitude))
            scale(scaleFor(camera.zoom))
            rotation(camera.rotation)
            minimumScaleMode(Forced(scaleFor(state.minZoom)))
            maxScale(scaleFor(state.maxZoom))
        }.apply {
            if (state.rotatable) enableRotation()
            setMapBackground(MAP_BACKGROUND)
        }
    }

    fun cameraOf(map: MapState) = MapCamera(
        center = GeoPoint(WebMercator.latitude(map.centroidY), WebMercator.longitude(map.centroidX)),
        zoom = zoomFor(map.scale),
        rotation = map.rotation,
    )

    /**
     * Moves [map] from code. A move takes two steps, the scale and then the
     * scroll: what the map reports between them is no place it was asked
     * to go, so [settled] reports once both are done.
     */
    fun driverFor(map: MapState, scope: CoroutineScope, settled: () -> Unit) = Driver(map, scope, settled)

    inner class Driver(
        private val map: MapState,
        private val scope: CoroutineScope,
        private val settled: () -> Unit,
    ) : LabMapState.Driver {
        /** Between the steps of a move */
        var moving = false
            private set

        override fun move(center: GeoPoint, zoom: Double) {
            scope.launch {
                moving = true
                try {
                    map.scale = scaleFor(zoom)
                    map.snapScrollTo(WebMercator.x(center.longitude), WebMercator.y(center.latitude))
                } finally {
                    moving = false
                }
                settled()
            }
        }

        override fun rotate(degrees: Float) {
            map.rotation = degrees
        }
    }

    private companion object {
        /** A web map tile, in dp */
        const val WEB_TILE = 256.0

        /** Zoom levels of the tile pyramid, 0 to 18 */
        const val MAX_LEVEL = 18

        /** Tiles fetched at once: they come from the network */
        const val WORKERS = 8
    }
}
