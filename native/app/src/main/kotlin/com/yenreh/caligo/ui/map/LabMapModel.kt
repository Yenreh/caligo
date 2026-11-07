package com.yenreh.caligo.ui.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.domain.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * What the map shows: its [center], its [zoom] as web maps count it (the
 * world 256 dp wide at zoom 0, twice as wide at each step), and how far
 * it is turned, clockwise, in degrees
 */
@Immutable
data class MapCamera(val center: GeoPoint, val zoom: Double, val rotation: Float = 0f)

/** A spot on the map that draws something and can be tapped */
@Immutable
data class LabMapMarker(val id: String, val position: GeoPoint)

/** A line through [points] */
@Immutable
data class LabMapPolyline(
    val id: String,
    val points: List<GeoPoint>,
    val color: Color,
    val width: Dp = 4.dp,
)

/**
 * Where the tiles come from: the app's tile servers, or a test's images.
 * Fetched off the main thread, many at once.
 */
interface MapTileSource {
    /** Tells one set of tiles from another: a different one is drawn afresh */
    val id: String

    /** The width of each tile image, in pixels: 256, or 512 for sharp tiles */
    val tilePixels: Int

    /** Draw the tiles with their colours inverted, for a dark map from light tiles */
    val inverted: Boolean

    /** Drawn by CARTO, which the credits then name */
    val byCarto: Boolean get() = false

    /** The encoded image of a tile, PNG or JPEG; null when there is none to draw */
    suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray?
}

/**
 * The camera of a [LabMap], followed as the map moves and moved from
 * code. Can outlive the map it drives: a new map opens where the last
 * one was left.
 */
@Stable
class LabMapState(
    initial: MapCamera,
    val minZoom: Double = 0.0,
    val maxZoom: Double = MAX_ZOOM,
) {
    /** Where the map is now; follows every gesture */
    var camera: MapCamera by mutableStateOf(initial.copy(zoom = initial.zoom.coerceIn(minZoom, maxZoom)))
        private set

    /** The map on screen, while there is one */
    internal var driver: Driver? = null

    /** What a [LabMap] on screen does when moved from code */
    internal interface Driver {
        fun move(center: GeoPoint, zoom: Double)

        fun rotate(degrees: Float)
    }

    /** Jump to [center] at [zoom], as flutter_map's MapController.move: no animation */
    fun move(center: GeoPoint, zoom: Double = camera.zoom) {
        val bounded = zoom.coerceIn(minZoom, maxZoom)
        camera = camera.copy(center = center, zoom = bounded)
        driver?.move(center, bounded)
    }

    /** Turn the map to [degrees] clockwise from north */
    fun rotate(degrees: Float) {
        camera = camera.copy(rotation = degrees)
        driver?.rotate(degrees)
    }

    /**
     * What the map reports as it moves. Its own arithmetic leaves noise in
     * the last digits, far below a millimetre on the ground: a camera that
     * differs by no more than that stays as it was, so a place moved to
     * reads back exactly as given.
     */
    internal fun follow(camera: MapCamera) {
        val current = this.camera
        if (abs(camera.center.latitude - current.center.latitude) < NOISE &&
            abs(camera.center.longitude - current.center.longitude) < NOISE &&
            abs(camera.zoom - current.zoom) < NOISE &&
            abs(camera.rotation - current.rotation) < ROTATION_NOISE
        ) {
            return
        }
        this.camera = camera
    }

    companion object {
        /** The deepest zoom the tile servers draw at */
        const val MAX_ZOOM = 18.0

        /** In degrees and zoom steps: about 0.1 mm on the ground */
        private const val NOISE = 1e-9

        /** In degrees of turn */
        private const val ROTATION_NOISE = 1e-4f
    }
}

/** Web Mercator, on a square of side 1 with the north-west corner at 0 */
internal object WebMercator {
    /** Beyond this the projection runs to infinity: the square's edge */
    private const val MAX_LATITUDE = 85.05112878

    fun x(longitude: Double): Double = (longitude + 180) / 360

    fun y(latitude: Double): Double {
        val radians = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180
        return (1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2
    }

    fun longitude(x: Double): Double = x * 360 - 180

    fun latitude(y: Double): Double = atan(sinh(PI * (1 - 2 * y))) * 180 / PI
}
