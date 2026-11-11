package com.yenreh.caligo.data.map

import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.dropped
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tiles through the network and the disk cache, as flutter_map's
 * NetworkTileImageProvider and its built-in caching provider fetched them
 * over the Flutter app's RetryClient
 */
class MapTileLoaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private var service: MockService? = null

    @After
    fun close() {
        service?.close()
    }

    private val url = "https://basemaps.cartocdn.com/rastertiles/voyager/16/18835/32139.png?key=k"
    private val headers = mapOf("User-Agent" to "CaliGo test", "X-Android-Package" to "com.yenreh.caligo")
    private val image = ByteArray(200) { (it % 251).toByte() }

    /** Now, as the loader's clock reads it: a fixed instant */
    private val now = 1_790_000_000_000L

    private val cache by lazy { MapTileCache(temporary.newFolder("fm_cache")) }

    private fun tiles(respond: (call: Int, url: HttpUrl) -> MockResponse): MapTileLoader {
        val mock = MockService(respond).also { service = it }
        return MapTileLoader(mock.client, cache, now = { now }, retryDelay = { Duration.ZERO })
    }

    private fun png(vararg headers: Pair<String, String>) = MockResponse.Builder()
        .code(200)
        .headers(Headers.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build())
        .body(Buffer().write(image))
        .build()

    private fun httpDate(millis: Long) = Headers.Builder().add("Date", Date(millis)).build()["Date"]!!

    @Test
    fun fetchesATileWithTheAppsHeadersAndKeepsIt() = runTest {
        val loader = tiles { _, _ -> png("Cache-Control" to "public, max-age=2592000", "Date" to httpDate(now)) }

        val bytes = loader.load(url, headers)

        assertArrayEquals(image, bytes)
        val sent = service!!.headers.single()
        assertEquals("CaliGo test", sent["User-Agent"])
        assertEquals("com.yenreh.caligo", sent["X-Android-Package"])
        assertEquals(url, service!!.requested.single().toString())
        val kept = cache.get(url)!!
        assertArrayEquals(image, kept.bytes)
        assertEquals(now + 2_592_000_000, kept.metadata.staleAt)
    }

    @Test
    fun aFreshTileAnswersWithoutAsking() = runTest {
        cache.put(url, TileMetadata(staleAt = now + 1000, lastModified = null, etag = null), image)
        val loader = tiles { _, _ -> error("Not to be asked") }

        assertArrayEquals(image, loader.load(url, headers))
        assertEquals(0, service!!.calls)
    }

    @Test
    fun anOldTileIsAskedAboutAndKeptWhenNotModified() = runTest {
        val modified = 1_780_000_000_000L
        cache.put(url, TileMetadata(staleAt = now - 1, lastModified = modified, etag = "\"v1\""), image)
        val loader = tiles { _, _ ->
            MockResponse(code = 304, headers = headersOf("Cache-Control", "max-age=60", "Age", "10", "ETag", "\"v1\""))
        }

        assertArrayEquals(image, loader.load(url, headers))

        val sent = service!!.headers.single()
        assertEquals("\"v1\"", sent["If-None-Match"])
        assertEquals(httpDate(modified), sent["If-Modified-Since"])
        // The new headers say how long it keeps now: max-age less its age
        assertEquals(now + 50_000, cache.get(url)!!.metadata.staleAt)
        assertArrayEquals(image, cache.get(url)!!.bytes)
    }

    @Test
    fun anOldTileChangedIsReplaced() = runTest {
        cache.put(url, TileMetadata(staleAt = now - 1, lastModified = null, etag = "\"v1\""), ByteArray(10))
        val loader = tiles { _, _ -> png("ETag" to "\"v2\"") }

        assertArrayEquals(image, loader.load(url, headers))
        assertEquals("\"v2\"", cache.get(url)!!.metadata.etag)
        assertArrayEquals(image, cache.get(url)!!.bytes)
    }

    @Test
    fun withoutCachingHeadersATileKeepsAWeek() = runTest {
        val loader = tiles { _, _ -> png() }

        loader.load(url, headers)

        assertEquals(now + 7.days.inWholeMilliseconds, cache.get(url)!!.metadata.staleAt)
    }

    @Test
    fun anExpiresHeaderSaysWhenWithoutAMaxAge() = runTest {
        val expires = now + 3_600_000
        val loader = tiles { _, _ -> png("Cache-Control" to "public", "Expires" to httpDate(expires)) }

        loader.load(url, headers)

        assertEquals(expires, cache.get(url)!!.metadata.staleAt)
    }

    @Test
    fun aMaxAgeCountsFromTheServersDate() = runTest {
        val loader = tiles { _, _ -> png("Cache-Control" to "max-age=600", "Date" to httpDate(now - 100_000)) }

        loader.load(url, headers)

        assertEquals(now + 500_000, cache.get(url)!!.metadata.staleAt)
    }

    @Test
    fun aMaxAgeWithoutADateCountsFromNow() = runTest {
        // flutter_map read the Date header without checking it was there, and kept nothing
        val loader = tiles { _, _ -> png("Cache-Control" to "max-age=600") }

        assertArrayEquals(image, loader.load(url, headers))
        assertEquals(now + 600_000, cache.get(url)!!.metadata.staleAt)
    }

    @Test
    fun eachTileSaysHowAskingForItWent() = runTest {
        fun outcome(respond: (call: Int, url: HttpUrl) -> MockResponse): TileOutcome {
            service?.close()
            return kotlinx.coroutines.runBlocking { tiles(respond).fetch(url, headers).outcome }
        }
        assertEquals(TileOutcome.ANSWERED, outcome { _, _ -> png() })
        // Fresh on disk now: nothing asked
        assertEquals(TileOutcome.KEPT, kotlinx.coroutines.runBlocking { tiles { _, _ -> png() }.fetch(url, headers).outcome })
        cache.put(url, TileMetadata(staleAt = now - 1, lastModified = null, etag = null), image)
        // The server refusing, an old tile drawn all the same
        val refused = kotlinx.coroutines.runBlocking { tiles { _, _ -> MockResponse(code = 403) }.fetch(url, headers) }
        assertEquals(TileOutcome.REFUSED, refused.outcome)
        assertArrayEquals(image, refused.bytes)
        assertEquals(TileOutcome.REFUSED, outcome { _, _ -> MockResponse(code = 401) })
        assertEquals(TileOutcome.REFUSED, outcome { _, _ -> MockResponse(code = 429) })
        // Busy past the retries
        assertEquals(TileOutcome.REFUSED, outcome { _, _ -> MockResponse(code = 503) })
        assertEquals(TileOutcome.REFUSED, outcome { _, _ -> MockResponse(code = 500) })
        assertEquals(TileOutcome.MISSING, outcome { _, _ -> MockResponse(code = 404) })
        assertEquals(TileOutcome.UNREACHABLE, outcome { _, _ -> dropped() })
    }

    @Test
    fun aBusyServerIsAskedAgainUpToThreeTimes() = runTest {
        val loader = tiles { call, _ -> if (call < 4) MockResponse(code = 503) else png() }

        assertArrayEquals(image, loader.load(url, headers))
        assertEquals(4, service!!.calls)
    }

    @Test
    fun aServerBusyFourTimesGivesNoTile() = runTest {
        val loader = tiles { _, _ -> MockResponse(code = 503) }

        assertNull(loader.load(url, headers))
        assertEquals(4, service!!.calls)
    }

    @Test
    fun anErrorWithABodyIsDrawnButNeverKept() = runTest {
        val loader = tiles { _, _ -> MockResponse.Builder().code(403).body(Buffer().write(image)).build() }

        assertArrayEquals(image, loader.load(url, headers))
        assertNull(cache.get(url))
    }

    @Test
    fun anEmptyErrorOrNoConnectionGivesNoTile() = runTest {
        assertNull(tiles { _, _ -> MockResponse(code = 404) }.load(url, headers))
        service?.close()

        assertNull(tiles { _, _ -> dropped() }.load(url, headers))
        // No attempt beyond the first: the Flutter app's client retried 503s only
        assertEquals(1, service!!.calls)
    }

    @Test
    fun anOldTileThatCannotBeCheckedIsDrawnAsItIs() = runTest {
        // flutter_map drew no tile: a blank map on a spotty connection
        cache.put(url, TileMetadata(staleAt = now - 1, lastModified = null, etag = null), image)

        assertArrayEquals(image, tiles { _, _ -> dropped() }.load(url, headers))
        // Kept as it was, to be checked again next time
        assertEquals(now - 1, cache.get(url)!!.metadata.staleAt)
    }

    @Test
    fun anOldTileBeatsAServerThatFails() = runTest {
        cache.put(url, TileMetadata(staleAt = now - 1, lastModified = null, etag = null), image)

        assertArrayEquals(image, tiles { _, _ -> MockResponse(code = 503) }.load(url, headers))
        service?.close()
        // An error explained in an image is not the map either
        val explained = ByteArray(10) { 1 }
        assertArrayEquals(
            image,
            tiles { _, _ -> MockResponse.Builder().code(403).body(Buffer().write(explained)).build() }.load(url, headers),
        )
    }
}
