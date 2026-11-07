package com.yenreh.caligo.data.map

import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** When a kept tile is to be asked about again, and what to ask with */
data class TileMetadata(
    /** Epoch milliseconds */
    val staleAt: Long,
    /** Epoch milliseconds of the server's Last-Modified, when it sent one */
    val lastModified: Long?,
    val etag: String?,
)

/** A tile as kept on disk */
class CachedTile(val metadata: TileMetadata, val bytes: ByteArray)

/**
 * Disk cache for the map tiles, in the files flutter_map's built-in
 * caching provider writes, where the Flutter app kept them
 * (`cache/fm_cache`): the port reads that app's tiles as they are.
 *
 * One file per tile, named by a UUID v5 of its URL without the API key:
 * the key says nothing about the image, and keeping it in the name would
 * throw the whole cache away whenever it changes. Over [maxBytes], the
 * tiles read longest ago go, in the background: from the first use, as
 * flutter_map trims at start, and again whenever the tiles written since
 * pass it, which flutter_map left for the next start.
 */
class MapTileCache(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    /** Runs a trim off the thread that asked for a tile */
    private val inBackground: (() -> Unit) -> Unit = { thread(name = "map-tile-trim", isDaemon = true, block = it) },
) {
    private val trimming = AtomicBoolean(false)

    /** What the tiles take, as last counted plus what was written since; -1 before the first count */
    private val counted = AtomicLong(-1)

    /** Write files get a name of their own, so two writers never share one */
    private val writes = AtomicLong()

    /** The kept tile for [url], however old; null when none, or unreadable */
    fun get(url: String): CachedTile? {
        trimIfDue()
        val file = fileFor(url)
        if (!file.isFile) return null
        return try {
            parse(file.readBytes())
        } catch (_: Exception) {
            // A tile cut short or written by something else: fetched again
            // and written over
            null
        }
    }

    /**
     * Keep [bytes] for [url] with [metadata]; with no bytes, as after a
     * Not Modified answer, only the metadata changes
     */
    fun put(url: String, metadata: TileMetadata, bytes: ByteArray?) {
        trimIfDue()
        val file = fileFor(url)
        try {
            val tile = bytes ?: get(url)?.bytes ?: return
            directory.mkdirs()
            val before = if (file.isFile) file.length() else 0
            val temporary = File(directory, "${file.name}.${writes.incrementAndGet()}.tmp")
            temporary.writeBytes(encode(metadata, tile))
            if (!temporary.renameTo(file)) {
                temporary.delete()
                return
            }
            // Once counted, the tiles written are added, and past the ceiling trimmed
            if (counted.get() >= 0 && counted.addAndGet(file.length() - before) > maxBytes) trimIfDue()
        } catch (_: Exception) {
            // Without the disk, the tile is simply fetched again next time
        }
    }

    /** What the tiles take on disk */
    fun sizeInBytes(): Long = try {
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0
    }

    /** Throw every tile away */
    fun clear() {
        directory.deleteRecursively()
        counted.set(0)
    }

    /**
     * Trim in the background, one at a time: on the first use, to count
     * the tiles, and whenever those written since take them past the
     * ceiling. During a run it leaves a tenth of room, so the next tiles
     * do not trim again one by one.
     */
    private fun trimIfDue() {
        val bytes = counted.get()
        if (bytes in 0..maxBytes) return
        val target = if (bytes < 0) maxBytes else maxBytes - maxBytes / 10
        if (!trimming.compareAndSet(false, true)) return
        inBackground {
            try {
                trim(target)
            } finally {
                trimming.set(false)
            }
        }
    }

    /**
     * Over [target], drop the tiles read longest ago until it is met, the
     * largest first among those read at the same time
     */
    internal fun trim(target: Long = maxBytes) {
        try {
            val files = directory.listFiles()?.filter { it.isFile && it.name != SIZE_MONITOR }.orEmpty()
            val total = files.sumOf { it.length() }
            if (total <= target) {
                counted.set(total)
                return
            }
            val sorted = files.map { Triple(it, it.length(), lastRead(it)) }
                .sortedWith(compareBy<Triple<File, Long, Long>> { it.third }.thenByDescending { it.second })
            var deleted = 0L
            for ((file, size) in sorted) {
                if (deleted >= total - target) break
                deleted += size
                file.delete()
            }
            counted.set(total - deleted)
        } catch (_: Exception) {
            // Trimming is housekeeping: failing to do it changes nothing.
            // Counted as empty, so it is tried again only past the ceiling.
            counted.compareAndSet(-1, 0)
        }
    }

    /** When [file] was last read, as flutter_map sorts them; its last write where that cannot be told */
    private fun lastRead(file: File): Long = try {
        Os.stat(file.path).st_atime * 1000
    } catch (_: Throwable) {
        file.lastModified()
    }

    private fun fileFor(url: String) = File(directory, tileKey(url))

    private fun parse(bytes: ByteArray): CachedTile {
        require(bytes.size >= MIN_SIZE) { "Too short" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in SIGNATURE.indices) require(bytes[i] == SIGNATURE[i]) { "Not a tile file" }
        require(buffer.getShort(6).toInt() and 0xFFFF == VERSION) { "Other version" }
        val staleAt = buffer.getLong(8)
        val lastModified = buffer.getLong(16).takeIf { it != 0L }
        val etagLength = buffer.getShort(24).toInt() and 0xFFFF
        val etag = if (etagLength == 0) null else String(bytes, 26, etagLength, Charsets.US_ASCII)
        val expected = buffer.getInt(26 + etagLength).toLong() and 0xFFFFFFFFL
        val start = 26 + etagLength + 4
        require(bytes.size - start.toLong() == expected) { "Tile bytes cut short" }
        return CachedTile(TileMetadata(staleAt, lastModified, etag), bytes.copyOfRange(start, bytes.size))
    }

    private fun encode(metadata: TileMetadata, tile: ByteArray): ByteArray {
        val etag = metadata.etag?.toByteArray(Charsets.US_ASCII)?.takeIf { it.size <= 0xFFFF } ?: ByteArray(0)
        return ByteBuffer.allocate(26 + etag.size + 4 + tile.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(SIGNATURE)
            .putShort(VERSION.toShort())
            .putLong(metadata.staleAt)
            .putLong(metadata.lastModified ?: 0)
            .putShort(etag.size.toShort())
            .put(etag)
            .putInt(tile.size)
            .put(tile)
            .array()
    }

    companion object {
        /** Enough for the city at every zoom the map allows, many times over */
        const val MAX_BYTES = 100L * 1024 * 1024

        /** Where flutter_map keeps the tiles, under the app's cache directory */
        const val DIRECTORY = "fm_cache"

        /** flutter_map's running total; not kept here, only left out of the trim */
        private const val SIZE_MONITOR = "sizeMonitor.bin"

        private val SIGNATURE = byteArrayOf(70, 77, 66, 73, 67, 84)
        private const val VERSION = 1
        private const val MIN_SIZE = 30

        private val URL_NAMESPACE = byteArrayOf(
            0x6b, 0xa7.toByte(), 0xb8.toByte(), 0x11, 0x9d.toByte(), 0xad.toByte(), 0x11, 0xd1.toByte(),
            0x80.toByte(), 0xb4.toByte(), 0x00, 0xc0.toByte(), 0x4f, 0xd4.toByte(), 0x30, 0xc8.toByte(),
        )

        private const val HEX = "0123456789abcdef"

        private val KEY_PARAMETER = Regex("[?&]key=[^&]*")

        /**
         * The file name of [url]'s tile: a UUID v5 in the URL namespace of
         * the URL without its API key, as the Flutter app's
         * MapTileCache names them
         */
        fun tileKey(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").run {
                update(URL_NAMESPACE)
                update(KEY_PARAMETER.replaceFirst(url, "").toByteArray(Charsets.UTF_8))
                digest()
            }
            digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
            digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = buildString {
                for (i in 0 until 16) {
                    val byte = digest[i].toInt() and 0xFF
                    append(HEX[byte shr 4]).append(HEX[byte and 0x0F])
                }
            }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}
