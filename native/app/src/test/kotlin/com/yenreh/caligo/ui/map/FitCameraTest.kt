package com.yenreh.caligo.ui.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.domain.GeoPoint
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Framing points on the map, as flutter_map's CameraFit.coordinates */
class FitCameraTest {
    @Test
    fun framesTheBoundsInsideThePaddingCentredOnThem() {
        // A square in Mercator: 1/1024 of the world each way
        val west = WebMercator.longitude(0.5)
        val east = WebMercator.longitude(0.5 + 1.0 / 1024)
        val north = WebMercator.latitude(0.5 - 1.0 / 1024)
        val south = WebMercator.latitude(0.5)

        val camera = fitCamera(
            listOf(GeoPoint(north, west), GeoPoint(south, east)),
            DpSize(480.dp, 380.dp),
            40.dp,
            minZoom = 0.0,
            maxZoom = 18.0,
        )

        // The height limits it: 300 dp for 1/1024 of a 256 dp world
        assertEquals(log2(300.0 * 1024 / 256), camera.zoom, 1e-9)
        assertEquals(WebMercator.latitude(0.5 - 0.5 / 1024), camera.center.latitude, 1e-9)
        assertEquals(WebMercator.longitude(0.5 + 0.5 / 1024), camera.center.longitude, 1e-9)
        assertEquals(0f, camera.rotation)
    }

    @Test
    fun theZoomStaysWithinItsBounds() {
        val one = listOf(GeoPoint(3.45, -76.53))
        val city = listOf(GeoPoint(3.30, -76.60), GeoPoint(3.50, -76.40))
        val size = DpSize(400.dp, 300.dp)

        // One point: as deep as allowed
        assertEquals(18.0, fitCamera(one, size, 40.dp, 11.0, 18.0).zoom, 0.0)
        assertEquals(GeoPoint(3.45, -76.53).latitude, fitCamera(one, size, 40.dp, 11.0, 18.0).center.latitude, 1e-9)
        // Further out than allowed: the least zoom
        assertEquals(13.0, fitCamera(city, size, 40.dp, 13.0, 18.0).zoom, 0.0)
        // No room inside the padding
        assertEquals(11.0, fitCamera(city, DpSize(60.dp, 60.dp), 40.dp, 11.0, 18.0).zoom, 0.0)
    }

    @Test
    fun aStateFitsOnlyOnceLaidOut() {
        val state = LabMapState(MapCamera(GeoPoint(3.45, -76.53), 15.0), minZoom = 11.0, maxZoom = 18.0)
        val route = listOf(GeoPoint(3.40, -76.53), GeoPoint(3.44, -76.53))

        state.fit(route, 40.dp)
        assertEquals(MapCamera(GeoPoint(3.45, -76.53), 15.0), state.camera)
        assertNull(state.viewport)

        state.viewport = DpSize(400.dp, 300.dp)
        state.fit(route, 40.dp)
        assertEquals(fitCamera(route, DpSize(400.dp, 300.dp), 40.dp, 11.0, 18.0), state.camera)
    }
}
