package com.yenreh.caligo.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.domain.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app's map over MapCompose, with tiles drawn here: which tiles it
 * asks for, where it draws markers and lines, how it follows gestures and
 * moves from code, and which marker a tap lands on
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class LabMapTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val cali = GeoPoint(3.4516, -76.5320)
    private val ermita = GeoPoint(3.4531, -76.5310)

    private val tapped = mutableListOf<String>()

    private fun show(
        state: LabMapState,
        tiles: MapTileSource = FixtureTiles(),
        markers: List<LabMapMarker> = emptyList(),
        polylines: List<LabMapPolyline> = emptyList(),
    ) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                LabMap(
                    state,
                    tiles,
                    Modifier
                        .fillMaxSize()
                        .testTag("map"),
                    markers = markers,
                    markerContent = { id -> Box(Modifier.size(24.dp).background(Color.Red).testTag("marker_$id")) },
                    onMarkerTap = { tapped.add(it) },
                    polylines = polylines,
                    fadeIn = false,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The tile of a web map holding [point] at [zoom], as zoom/x/y */
    private fun tileOf(point: GeoPoint, zoom: Int): String {
        val n = 2.0.pow(zoom)
        val x = floor((point.longitude + 180) / 360 * n).toInt()
        val radians = point.latitude * PI / 180
        val y = floor((1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * n).toInt()
        return "$zoom/$x/$y"
    }

    private fun waitForTile(tiles: FixtureTiles, tile: String) = compose.waitUntil(10_000) { tile in tiles.asked }

    /** Where a node's centre lies on screen, in pixels */
    private fun centreOf(tag: String): Offset {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return bounds.center
    }

    /** The window as drawn, to read its pixels */
    private fun window(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** Degrees of longitude in one screen pixel at [zoom] */
    private fun degreesPerPixel(zoom: Double): Double {
        val density = compose.activity.resources.displayMetrics.density
        return 360 / (256 * density * 2.0.pow(zoom))
    }

    // Tiles

    @Test
    fun asksForTheTilesOfTheNearestZoom() {
        val tiles = FixtureTiles()
        show(LabMapState(MapCamera(cali, 16.4)), tiles)
        waitForTile(tiles, tileOf(cali, 16))
        assertTrue(tiles.asked.all { it.startsWith("16/") })
    }

    @Test
    fun pastHalfwayAsksForTheNextZoom() {
        val tiles = FixtureTiles()
        show(LabMapState(MapCamera(cali, 16.6)), tiles)
        waitForTile(tiles, tileOf(cali, 17))
        assertTrue(tiles.asked.all { it.startsWith("17/") })
    }

    @Test
    fun sharpTilesAreAskedAtTheSameZoom() {
        val tiles = FixtureTiles(tilePixels = 512)
        show(LabMapState(MapCamera(cali, 15.4)), tiles)
        waitForTile(tiles, tileOf(cali, 15))
        assertTrue(tiles.asked.all { it.startsWith("15/") })
    }

    @Test
    fun onlyTheTilesOnScreen() {
        val tiles = FixtureTiles()
        show(LabMapState(MapCamera(cali, 16.0)), tiles)
        waitForTile(tiles, tileOf(cali, 16))
        Thread.sleep(300)
        compose.waitForIdle()

        // 360 x 640 dp is 1.4 by 2.5 tiles of 256 dp: never more than 3 by 4
        val asked = tiles.asked.toSet()
        assertTrue("asked $asked", asked.size in 4..12)
    }

    @Test
    fun drawsTheTilesItIsGiven() {
        val tiles = FixtureTiles()
        show(LabMapState(MapCamera(cali, 16.0)), tiles)
        waitForTile(tiles, tileOf(cali, 16))
        compose.waitUntil(10_000) {
            // The fixture's paper, not the grey under the tiles
            window().getPixel(5, 5) != 0xFFE0E0E0.toInt()
        }
    }

    @Test
    fun aDarkMapFromLightTilesIsDrawnInverted() {
        val light = FixtureTiles()
        show(LabMapState(MapCamera(cali, 16.0)), FixtureTiles(inverted = true, id = "inverted"))
        compose.waitUntil(10_000) { window().getPixel(5, 5) != 0xFFE0E0E0.toInt() }
        val pixel = window().getPixel(5, 5)
        // The fixture's light paper comes out dark
        val brightness = (pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)
        assertTrue("pixel ${Integer.toHexString(pixel)}", brightness < 3 * 128)
        assertTrue(light.asked.isEmpty())
    }

    // Markers and lines

    @Test
    fun drawsAMarkerCentredOnItsPlace() {
        show(LabMapState(MapCamera(cali, 16.0)), markers = listOf(LabMapMarker("a", cali)))
        compose.waitUntil(5_000) { exists(hasTestTag("marker_a")) }

        val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot.center
        val marker = centreOf("marker_a")
        assertEquals(map.x, marker.x, 2f)
        assertEquals(map.y, marker.y, 2f)
    }

    @Test
    fun placesEachMarkerAtItsDistance() {
        show(LabMapState(MapCamera(cali, 17.0)), markers = listOf(LabMapMarker("a", cali), LabMapMarker("b", ermita)))
        compose.waitUntil(5_000) { exists(hasTestTag("marker_b")) }

        val a = centreOf("marker_a")
        val b = centreOf("marker_b")
        // East is right and north up
        val expectedDx = (ermita.longitude - cali.longitude) / degreesPerPixel(17.0)
        assertEquals(expectedDx.toFloat(), b.x - a.x, 2f)
        assertTrue(b.y < a.y)
    }

    @Test
    fun markersFollowTheList() {
        var markers by mutableStateOf(listOf(LabMapMarker("a", cali)))
        compose.setContent {
            LabMap(
                LabMapState(MapCamera(cali, 16.0)),
                FixtureTiles(),
                Modifier.fillMaxSize(),
                markers = markers,
                markerContent = { id -> Box(Modifier.size(24.dp).testTag("marker_$id")) },
                fadeIn = false,
            )
        }
        compose.waitUntil(5_000) { exists(hasTestTag("marker_a")) }

        markers = listOf(LabMapMarker("b", ermita))

        compose.waitUntil(5_000) { exists(hasTestTag("marker_b")) && !exists(hasTestTag("marker_a")) }
    }

    @Test
    fun aTapOnAMarkerSaysWhich() {
        show(
            LabMapState(MapCamera(cali, 17.0)),
            markers = listOf(LabMapMarker("a", cali), LabMapMarker("b", ermita)),
        )
        compose.waitUntil(5_000) { exists(hasTestTag("marker_b")) }

        compose.onNodeWithTag("marker_b", useUnmergedTree = true).performTouchInput { click() }

        compose.waitUntil(5_000) { tapped.isNotEmpty() }
        assertEquals(listOf("b"), tapped)
    }

    @Test
    fun aTapAwayFromTheMarkersSaysNothing() {
        show(LabMapState(MapCamera(cali, 17.0)), markers = listOf(LabMapMarker("a", cali)))
        compose.waitUntil(5_000) { exists(hasTestTag("marker_a")) }

        compose.onNodeWithTag("map").performTouchInput { click(Offset(20f, 20f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertTrue(tapped.isEmpty())
    }

    @Test
    fun drawsALineThroughItsPoints() {
        val east = GeoPoint(cali.latitude, cali.longitude + 0.002)
        show(
            LabMapState(MapCamera(cali, 16.0)),
            tiles = FixtureTiles(dark = true),
            polylines = listOf(LabMapPolyline("route", listOf(cali, east), Color(0xFF0000FF), width = 6.dp)),
        )
        compose.waitUntil(10_000) { window().getPixel(5, 5) != 0xFFE0E0E0.toInt() }

        // Halfway along the line, right of the centre, on the screen's middle row
        val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        val halfway = 0.001 / degreesPerPixel(16.0)
        val pixel = window().getPixel((map.center.x + halfway).toInt(), map.center.y.toInt())
        assertEquals("pixel ${Integer.toHexString(pixel)}", 0xFF0000FF.toInt(), pixel)
        // Nothing of it above
        val above = window().getPixel((map.center.x + halfway).toInt(), (map.center.y - 40).toInt())
        assertTrue(above != 0xFF0000FF.toInt())
    }

    // The camera

    @Test
    fun movesFromCodeAtOnce() {
        val tiles = FixtureTiles()
        val state = LabMapState(MapCamera(cali, 15.0))
        show(state, tiles, markers = listOf(LabMapMarker("b", ermita)))

        compose.runOnUiThread { state.move(ermita, 17.0) }
        compose.waitForIdle()

        assertEquals(ermita.latitude, state.camera.center.latitude, 1e-6)
        assertEquals(ermita.longitude, state.camera.center.longitude, 1e-6)
        assertEquals(17.0, state.camera.zoom, 1e-6)
        waitForTile(tiles, tileOf(ermita, 17))
        val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot.center
        assertEquals(map.x, centreOf("marker_b").x, 2f)
    }

    @Test
    fun keepsTheZoomWithinItsBounds() {
        val state = LabMapState(MapCamera(cali, 9.0), minZoom = 11.0, maxZoom = 18.0)
        assertEquals(11.0, state.camera.zoom, 0.0)
        show(state)

        compose.runOnUiThread { state.move(cali, 20.0) }
        compose.waitForIdle()

        assertEquals(18.0, state.camera.zoom, 1e-6)
    }

    @Test
    fun followsAPan() {
        val state = LabMapState(MapCamera(cali, 16.0))
        show(state)

        // Slowly, so nothing flings: the map goes with the finger
        compose.onNodeWithTag("map").performTouchInput {
            swipe(start = center, end = center - Offset(300f, 0f), durationMillis = 2_000)
        }
        compose.waitForIdle()

        // All of it but the touch slop, which only tells a drag from a tap
        val moved = (state.camera.center.longitude - cali.longitude) / degreesPerPixel(16.0)
        assertTrue("moved $moved px", moved in 220.0..305.0)
        assertEquals(cali.latitude, state.camera.center.latitude, 1e-4)
    }

    @Test
    fun aFlingCarriesOnPastTheFinger() {
        val state = LabMapState(MapCamera(cali, 16.0))
        show(state)

        compose.onNodeWithTag("map").performTouchInput {
            swipeWithVelocity(start = center, end = center - Offset(200f, 0f), endVelocity = 4_000f, durationMillis = 100)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        val moved = state.camera.center.longitude - cali.longitude
        assertTrue("moved $moved", moved > 300 * degreesPerPixel(16.0))
    }

    @Test
    fun followsAPinch() {
        val state = LabMapState(MapCamera(cali, 15.0), minZoom = 11.0)
        show(state)

        compose.onNodeWithTag("map").performTouchInput {
            pinch(
                start0 = center - Offset(50f, 0f),
                end0 = center - Offset(250f, 0f),
                start1 = center + Offset(50f, 0f),
                end1 = center + Offset(250f, 0f),
                durationMillis = 1_000,
            )
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        // Fingers five times as far apart: a bit over two zoom steps in
        assertTrue("zoom ${state.camera.zoom}", state.camera.zoom > 16.5)
        assertEquals(cali.longitude, state.camera.center.longitude, 1e-3)
    }

    @Test
    fun turnsFromCodeAndReportsIt() {
        val state = LabMapState(MapCamera(cali, 16.0))
        show(state)

        compose.runOnUiThread { state.rotate(30f) }
        compose.waitForIdle()

        assertEquals(30f, state.camera.rotation, 0.01f)
        assertEquals(cali.longitude, state.camera.center.longitude, 1e-6)
    }

    @Test
    fun aNewMapOpensWhereTheLastWasLeft() {
        val state = LabMapState(MapCamera(cali, 15.0))
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) LabMap(state, FixtureTiles(), Modifier.fillMaxSize().testTag("map"), fadeIn = false)
        }
        compose.waitForIdle()
        compose.runOnUiThread { state.move(ermita, 17.0) }
        compose.waitForIdle()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()

        assertEquals(ermita.longitude, state.camera.center.longitude, 1e-6)
        assertEquals(17.0, state.camera.zoom, 1e-6)
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun onADenseScreenTilesStillFollowTheZoom() {
        // 420 dpi: tiles of 256 dp drawn from 256 pixels at the nearest zoom
        val tiles = FixtureTiles()
        show(LabMapState(MapCamera(cali, 16.4)), tiles)
        waitForTile(tiles, tileOf(cali, 16))
        assertTrue(tiles.asked.all { it.startsWith("16/") })
        assertTrue(abs(compose.activity.resources.displayMetrics.density - 2.625f) < 0.01f)
    }
}
