package com.yenreh.caligo.data.map

import com.yenreh.caligo.WAIT_TIMEOUT_MS
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The tile cache in the files flutter_map's built-in caching provider
 * writes, as the Flutter app's MapTileCache set it up: named by a UUID v5
 * of the URL without its key, 100 MB at most
 */
class MapTileCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val folder by lazy { temporary.newFolder("fm_cache") }

    private fun cache(maxBytes: Long = MapTileCache.MAX_BYTES) = MapTileCache(folder, maxBytes)

    private val url = "https://basemaps.cartocdn.com/rastertiles/voyager/16/18835/32139.png"

    @Test
    fun namesATileByTheUuidOfItsUrl() {
        // uuid.uuid5(uuid.NAMESPACE_URL, url) in Python, as Dart's uuid v5
        assertEquals("6d5bbf99-2670-5473-9a3a-05db164496f0", MapTileCache.tileKey(url))
        assertEquals(
            "eed2df84-3586-53c9-8b21-b15608bd3cda",
            MapTileCache.tileKey("https://tile.openstreetmap.org/16/18835/32139.png"),
        )
    }

    @Test
    fun leavesTheApiKeyOutOfTheName() {
        assertEquals(MapTileCache.tileKey(url), MapTileCache.tileKey("$url?key=secret"))
        assertEquals(
            MapTileCache.tileKey("https://basemaps.cartocdn.com/rastertiles/dark_all/16/18835/32139@2x.png"),
            MapTileCache.tileKey("https://basemaps.cartocdn.com/rastertiles/dark_all/16/18835/32139@2x.png?key=other"),
        )
        assertEquals("3fdb420c-9e64-5828-ad6b-c1ef027f3fd3", MapTileCache.tileKey(
            "https://basemaps.cartocdn.com/rastertiles/dark_all/16/18835/32139@2x.png?key=other",
        ))
    }

    @Test
    fun keepsATileWithWhatTheServerSaidOfIt() {
        val cache = cache()
        val bytes = ByteArray(300) { it.toByte() }

        cache.put(url, TileMetadata(staleAt = 1_800_000_000_000, lastModified = 1_790_000_000_000, etag = "\"v1\""), bytes)

        val kept = cache.get(url)!!
        assertArrayEquals(bytes, kept.bytes)
        assertEquals(TileMetadata(1_800_000_000_000, 1_790_000_000_000, "\"v1\""), kept.metadata)
        assertTrue(File(folder, MapTileCache.tileKey(url)).isFile)
    }

    @Test
    fun writesTheFlutterMapFileFormat() {
        val cache = cache()
        cache.put(url, TileMetadata(staleAt = 1_800_000_000_123, lastModified = null, etag = "ab"), byteArrayOf(9, 8, 7))

        val file = File(folder, MapTileCache.tileKey(url)).readBytes()
        val buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("FMBICT", String(file, 0, 6, Charsets.US_ASCII))
        assertEquals(1, buffer.getShort(6).toInt())
        assertEquals(1_800_000_000_123, buffer.getLong(8))
        assertEquals(0L, buffer.getLong(16))
        assertEquals(2, buffer.getShort(24).toInt())
        assertEquals("ab", String(file, 26, 2, Charsets.US_ASCII))
        assertEquals(3, buffer.getInt(28))
        assertArrayEquals(byteArrayOf(9, 8, 7), file.copyOfRange(32, 35))
        assertEquals(35, file.size)
    }

    @Test
    fun readsATileTheFlutterAppKept() {
        // As flutter_map's writer lays it out, without an etag
        val tile = ByteArray(40) { (it * 3).toByte() }
        val file = ByteBuffer.allocate(30 + tile.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("FMBICT".toByteArray(Charsets.US_ASCII))
            .putShort(1)
            .putLong(1_800_000_000_000)
            .putLong(1_780_000_000_000)
            .putShort(0)
            .putInt(tile.size)
            .put(tile)
            .array()
        File(folder, MapTileCache.tileKey(url)).writeBytes(file)

        val kept = cache().get("$url?key=whatever")!!

        assertArrayEquals(tile, kept.bytes)
        assertEquals(TileMetadata(1_800_000_000_000, 1_780_000_000_000, null), kept.metadata)
    }

    @Test
    fun aTileCutShortOrForeignIsNoTile() {
        val cache = cache()
        cache.put(url, TileMetadata(1, null, null), ByteArray(100))
        val file = File(folder, MapTileCache.tileKey(url))
        file.writeBytes(file.readBytes().copyOf(80))
        assertNull(cache.get(url))

        file.writeBytes(ByteArray(64) { 1 })
        assertNull(cache.get(url))

        // Written over with fresh data
        cache.put(url, TileMetadata(2, null, null), ByteArray(10) { 5 })
        assertArrayEquals(ByteArray(10) { 5 }, cache.get(url)!!.bytes)
    }

    @Test
    fun aNotModifiedAnswerOnlyChangesTheMetadata() {
        val cache = cache()
        val bytes = ByteArray(50) { 7 }
        cache.put(url, TileMetadata(1, null, "\"a-much-longer-etag\""), bytes)

        cache.put(url, TileMetadata(99, 5, "\"b\""), bytes = null)

        val kept = cache.get(url)!!
        assertArrayEquals(bytes, kept.bytes)
        assertEquals(TileMetadata(99, 5, "\"b\""), kept.metadata)
    }

    @Test
    fun noBytesAndNoTileKeepsNothing() {
        val cache = cache()

        cache.put(url, TileMetadata(1, null, null), bytes = null)

        assertNull(cache.get(url))
        assertEquals(0L, cache.sizeInBytes())
    }

    @Test
    fun measuresAndClearsEverythingInItsFolder() {
        val cache = cache()
        cache.put(url, TileMetadata(1, null, null), ByteArray(1000))
        cache.put("$url?x", TileMetadata(1, null, null), ByteArray(500))
        File(folder, "sizeMonitor.bin").writeBytes(ByteArray(8))

        assertEquals(1030L + 530L + 8L, cache.sizeInBytes())

        cache.clear()

        assertFalse(folder.exists())
        assertEquals(0L, cache.sizeInBytes())
        assertNull(cache.get(url))
    }

    @Test
    fun overTheCeilingTheTilesReadLongestAgoGo() {
        // Three tiles of 1030 bytes on disk, read in this order
        val writer = cache()
        val urls = (1..3).map { "$url?$it" }
        for (u in urls) writer.put(u, TileMetadata(1, null, null), ByteArray(1000))
        urls.forEachIndexed { i, u -> File(folder, MapTileCache.tileKey(u)).setLastModified(1_000_000L * (i + 1)) }

        // Room for two: the oldest goes
        val cache = MapTileCache(folder, maxBytes = 2100)
        cache.trim()

        assertNull(cache.get(urls[0]))
        assertTrue(cache.get(urls[1]) != null)
        assertTrue(cache.get(urls[2]) != null)
        assertEquals(2060L, cache.sizeInBytes())
    }

    @Test
    fun passingTheCeilingDuringARunTrimsAgain() {
        // Trims run as they are asked for, so the test needs no thread
        val cache = MapTileCache(folder, maxBytes = 2100, inBackground = { it() })
        // The first use counts what is there: nothing
        assertNull(cache.get(url))
        val urls = (1..3).map { "$url?$it" }
        for ((i, u) in urls.withIndex()) {
            cache.put(u, TileMetadata(1, null, null), ByteArray(1000))
            File(folder, MapTileCache.tileKey(u)).setLastModified(1_000_000L * (i + 1))
        }

        // flutter_map trimmed at start only: the third tile went past it for the run
        assertTrue(cache.sizeInBytes() <= 2100)
        assertNull(cache.get(urls[0]))
        assertTrue(cache.get(urls[2]) != null)
    }

    @Test
    fun underTheCeilingNothingIsCountedAgain() {
        var trims = 0
        val cache = MapTileCache(folder, maxBytes = 10_000, inBackground = { trims++; it() })

        cache.get(url)
        for (i in 1..3) cache.put("$url?$i", TileMetadata(1, null, null), ByteArray(1000))
        cache.get(url)

        assertEquals(1, trims)
    }

    @Test
    fun theFirstUseTrimsInTheBackground() {
        val writer = cache()
        for (i in 1..3) writer.put("$url?$i", TileMetadata(1, null, null), ByteArray(1000))

        val cache = MapTileCache(folder, maxBytes = 1100)
        cache.get(url)

        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (cache.sizeInBytes() > 1100 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1030L, cache.sizeInBytes())
    }
}
