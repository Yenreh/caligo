package com.yenreh.caligo.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.data.map.CartoFallback
import com.yenreh.caligo.data.map.TileFetch
import com.yenreh.caligo.data.map.TileFetcher
import com.yenreh.caligo.data.map.TileOutcome
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A map with a CARTO key while CARTO refuses every tile: after three it
 * draws OpenStreetMap's, with OpenStreetMap's credits alone, under
 * build/outputs/roborazzi/map_fallback_osm.png
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class MapFallbackScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** CARTO refusing, OpenStreetMap's tiles drawn as the fixture's */
    private class Refused : TileFetcher {
        private val drawn = FixtureTiles()
        val cartoAsked: MutableList<String> = CopyOnWriteArrayList()
        val osmAsked: MutableList<String> = CopyOnWriteArrayList()

        override suspend fun fetch(url: String, headers: Map<String, String>): TileFetch {
            if ("openstreetmap" !in url) {
                cartoAsked.add(url)
                return TileFetch(null, TileOutcome.REFUSED)
            }
            osmAsked.add(url)
            val (z, x, y) = url.substringAfter(".org/").removeSuffix(".png").split("/").map { it.toInt() }
            return TileFetch(drawn.tile(z, x, y), TileOutcome.ANSWERED)
        }
    }

    @Test
    fun aMapCartoRefusesShowsOpenStreetMapWithItsCredits() {
        val fallback = CartoFallback()
        val tiles = Refused()
        compose.setContent {
            CaliGoTheme {
                val source = rememberAppMapTiles(Settings(), tiles, fallback, key = "k3y")
                Box(Modifier.fillMaxSize()) {
                    LabMap(
                        remember { LabMapState(MapCamera(GeoPoint(3.4516, -76.5320), 16.0)) },
                        source,
                        Modifier.fillMaxSize(),
                        fadeIn = false,
                    )
                    MapCredits(
                        open = true,
                        onToggle = {},
                        tiles = tilesPalette(dark = false),
                        carto = source.byCarto,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    )
                }
            }
        }
        compose.waitForMapTiles { fallback.switches.value == 1 && tiles.osmAsked.size >= 4 }
        compose.captureWindow("map_fallback_osm")
        assertEquals(1, fallback.switches.value)
        assertTrue(compose.onAllNodes(hasText("© CARTO")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("© OpenStreetMap contributors")).fetchSemanticsNodes().isNotEmpty())
    }
}
