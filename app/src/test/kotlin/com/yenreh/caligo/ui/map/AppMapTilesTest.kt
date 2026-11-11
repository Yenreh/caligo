package com.yenreh.caligo.ui.map

import com.yenreh.caligo.BuildConfig
import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.map.CartoFallback
import com.yenreh.caligo.data.map.MapTileCache
import com.yenreh.caligo.data.map.MapTileLoader
import com.yenreh.caligo.data.map.TileFetch
import com.yenreh.caligo.data.map.TileFetcher
import com.yenreh.caligo.data.map.TileOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** map_parts.dart's mapTileLayer: which tiles, how sharp, and how the app introduces itself */
class AppMapTilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val service = MockService { _, _ -> ok("tile") }

    @After
    fun close() = service.close()

    private val loader by lazy { MapTileLoader(service.client, MapTileCache(temporary.newFolder())) }

    private fun tiles(dark: Boolean = false, sharp: Boolean = true, density: Float = 2.625f, key: String = "k3y") =
        AppMapTiles(loader, dark, sharp, density, key)

    @Test
    fun withAKeyVoyagerInLightAndDarkMatterInDark() {
        assertEquals(
            "https://basemaps.cartocdn.com/rastertiles/voyager/16/18835/32139@2x.png?key=k3y",
            tiles().url(16, 18835, 32139),
        )
        assertEquals(
            "https://basemaps.cartocdn.com/rastertiles/dark_all/16/18835/32139@2x.png?key=k3y",
            tiles(dark = true).url(16, 18835, 32139),
        )
        assertFalse(tiles(dark = true).inverted)
        assertTrue(tiles().byCarto)
    }

    @Test
    fun sharpTilesOnlyWhenAskedOnADenseScreen() {
        assertEquals(512, tiles().tilePixels)
        assertEquals(256, tiles(sharp = false).tilePixels)
        assertEquals(
            "https://basemaps.cartocdn.com/rastertiles/voyager/16/1/2.png?key=k3y",
            tiles(sharp = false).url(16, 1, 2),
        )
        assertEquals(256, tiles(density = 1f).tilePixels)
        assertEquals(512, tiles(density = 1.5f).tilePixels)
    }

    @Test
    fun withoutAKeyOpenStreetMapInvertedForDark() {
        val light = tiles(key = "")
        val dark = tiles(dark = true, key = "")

        assertEquals("https://tile.openstreetmap.org/16/18835/32139.png", light.url(16, 18835, 32139))
        assertEquals(256, light.tilePixels)
        assertFalse(light.inverted)
        assertTrue(dark.inverted)
        assertFalse(dark.byCarto)
        assertEquals(light.url(1, 2, 3), dark.url(1, 2, 3))
    }

    @Test
    fun aNewStyleIsANewSetOfTiles() {
        assertTrue(tiles().id != tiles(dark = true).id)
        assertTrue(tiles().id != tiles(sharp = false).id)
        assertEquals(tiles(), tiles())
    }

    // CARTO refusing: OpenStreetMap in its place

    /** Tiles as a test scripts them: [outcome] for CARTO's, an image for OpenStreetMap's; every address asked kept */
    private class ScriptedTiles(var outcome: TileOutcome) : TileFetcher {
        val asked = mutableListOf<String>()

        override suspend fun fetch(url: String, headers: Map<String, String>): TileFetch {
            asked.add(url)
            if ("openstreetmap" in url) return TileFetch(byteArrayOf(1), TileOutcome.ANSWERED)
            return TileFetch(null, outcome)
        }
    }

    @Test
    fun threeRefusalsFromCartoMoveTheMapsToOpenStreetMapInvertedForDarkWithItsCredits() = runTest {
        val fallback = CartoFallback()
        val scripted = ScriptedTiles(TileOutcome.REFUSED)
        val carto = AppMapTiles(scripted, dark = true, sharp = true, density = 2.625f, key = "k3y", fallback = fallback)
        repeat(3) { carto.tile(16, 1, it) }
        assertEquals(1, fallback.switches.value)

        // What the map then shows: OpenStreetMap's tiles, light ones inverted, its credits alone
        val choice = fallback.choice()
        assertFalse(choice.carto)
        val osm = AppMapTiles(scripted, dark = true, sharp = true, density = 2.625f, key = "k3y", fallback = fallback, carto = false)
        assertEquals("osm", osm.id)
        assertTrue(osm.inverted)
        assertFalse(osm.byCarto)
        assertEquals(256, osm.tilePixels)
        assertEquals(byteArrayOf(1).toList(), osm.tile(16, 1, 2)?.toList())
        assertEquals("https://tile.openstreetmap.org/16/1/2.png", scripted.asked.last())
        // A different set of tiles: drawn afresh, never mixed with CARTO's
        assertTrue(osm.id != carto.id)
    }

    @Test
    fun noConnectionOrAMissingTileNeverSwitches() = runTest {
        val fallback = CartoFallback()
        val scripted = ScriptedTiles(TileOutcome.UNREACHABLE)
        val carto = AppMapTiles(scripted, dark = false, sharp = true, density = 2.625f, key = "k3y", fallback = fallback)
        repeat(5) { carto.tile(16, 1, it) }
        scripted.outcome = TileOutcome.MISSING
        repeat(5) { carto.tile(16, 2, it) }
        assertEquals(0, fallback.switches.value)
    }

    @Test
    fun aKeylessBuildIsOpenStreetMapAsBeforeAndTellsTheFallbackNothing() = runTest {
        val fallback = CartoFallback()
        val scripted = ScriptedTiles(TileOutcome.REFUSED)
        val osm = AppMapTiles(scripted, dark = false, sharp = true, density = 2.625f, key = "", fallback = fallback)
        repeat(5) { osm.tile(16, 1, it) }
        assertEquals("osm", osm.id)
        assertEquals(0, fallback.switches.value)
    }

    @Test
    fun cartoRequestsAnnounceCaliGoAndItsCertificate() = runTest {
        tiles().tile(16, 18835, 32139)

        val sent = service.headers.single()
        // CaliGo's package, the one the key is limited to, and the app's own
        assertEquals("com.yenreh.caligo", sent["X-Android-Package"])
        assertEquals("com.yenreh.caligo", BuildConfig.APPLICATION_ID)
        assertEquals("9B:AD:EB:EF:73:AF:2E:F5:95:AF:94:F9:00:B0:34:45:B3:E5:44:C2", sent["X-Android-Cert"])
        assertTrue(sent["User-Agent"]!!.startsWith("CaliGo/"))
        assertTrue("(com.yenreh.caligo;" in sent["User-Agent"]!!)
    }

    @Test
    fun openStreetMapGetsANamedClientAndNoKeyHeaders() = runTest {
        tiles(key = "").tile(16, 18835, 32139)

        val sent = service.headers.single()
        assertEquals(
            "CaliGo/${BuildConfig.VERSION_NAME} (com.yenreh.caligo; +https://github.com/Yenreh/CaliGo)",
            sent["User-Agent"],
        )
        assertEquals(null, sent["X-Android-Package"])
        assertEquals(null, sent["X-Android-Cert"])
        assertEquals("https://tile.openstreetmap.org/16/18835/32139.png", service.requested.single().toString())
    }
}
