package com.yenreh.caligo.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.BuildConfig
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.map.MapTileLoader
import com.yenreh.caligo.domain.entities.Settings

/**
 * The tiles of every map in the app, as `map_parts.dart`'s mapTileLayer.
 *
 * From CARTO when the build carries a key: its styles leave out the bus
 * stops and shops OpenStreetMap draws into its own tiles, which only add
 * noise next to the stops the app shows. Voyager's cream paper sits close
 * to the light theme; Dark Matter is the dark one, only when the settings
 * ask for it. [sharp] asks for tiles at the screen's full resolution, two
 * to three times the data, on any screen denser than one pixel a dp.
 *
 * Without a key, OpenStreetMap's, which only come light: those are
 * inverted for a dark map.
 */
class AppMapTiles(
    private val loader: MapTileLoader,
    dark: Boolean,
    sharp: Boolean,
    density: Float,
    /** Passed to the build from the environment or local.properties, never committed */
    key: String = BuildConfig.CARTO_KEY,
) : MapTileSource {
    private val fromCarto = key.isNotEmpty()
    override val byCarto get() = fromCarto

    private val style = if (dark) "dark_all" else "voyager"
    private val retina = fromCarto && sharp && density > 1f

    override val id = if (fromCarto) "carto/$style${if (retina) "@2x" else ""}" else "osm"
    override val tilePixels = if (retina) 512 else 256
    override val inverted = !fromCarto && dark

    private val template = if (fromCarto) {
        "https://basemaps.cartocdn.com/rastertiles/$style/{z}/{x}/{y}${if (retina) "@2x" else ""}.png?key=$key"
    } else {
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    }

    private val headers = if (fromCarto) CARTO_HEADERS else BASE_HEADERS

    /** The address of a tile, key included */
    fun url(zoom: Int, x: Int, y: Int): String =
        template.replace("{z}", zoom.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? = loader.load(url(zoom, x, y), headers)

    override fun equals(other: Any?) = other is AppMapTiles && other.id == id && other.loader === loader

    override fun hashCode() = id.hashCode()

    companion object {
        /**
         * Names the app to the tile servers, as OpenStreetMap's tile policy
         * asks: what it is and where to learn more. The Flutter app sent
         * flutter_map's, with CaliGo's package.
         */
        val USER_AGENT = "${AppInfo.NAME}/${BuildConfig.VERSION_NAME} (${AppInfo.PACKAGE_ID}; +${AppInfo.REPOSITORY})"

        private val BASE_HEADERS = mapOf("User-Agent" to USER_AGENT)

        /**
         * How CARTO tells this app from anyone else holding the key: its API
         * key is restricted to CaliGo's package signed with its certificate
         */
        val CARTO_HEADERS = BASE_HEADERS + mapOf(
            "X-Android-Package" to AppInfo.PACKAGE_ID,
            "X-Android-Cert" to AppInfo.SIGNING_SHA1,
        )
    }
}

/** The app's tiles for this screen's density, as the settings ask */
@Composable
fun rememberAppMapTiles(settings: Settings): MapTileSource {
    val loader = (LocalContext.current.applicationContext as CaliGoApp).container.tileLoader
    val density = LocalDensity.current.density
    return remember(loader, settings.darkMap, settings.sharpMap, density) {
        AppMapTiles(loader, settings.darkMap, settings.sharpMap, density)
    }
}
