package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.json.long
import com.yenreh.caligo.data.json.parseJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Small JSON cache backed by memory and by a file each, `<key>.json`
 * holding `{"savedAt": <epoch ms>, "value": ...}` as the Flutter app's.
 *
 * Used for the parts of the transit data that barely change, so the app
 * does not download them again on every launch.
 */
interface JsonCache {
    /** Cached value for [key], or null when missing or older than [maxAge] */
    suspend fun read(key: String, maxAge: Duration? = null): JsonElement?

    suspend fun write(key: String, value: JsonElement)

    /**
     * Delete entries not written in [maxAge].
     *
     * One file per stop would otherwise pile up for as long as the app is
     * installed, since every stop whose lines were looked up leaves one.
     */
    suspend fun prune(maxAge: Duration)

    /** Cache that keeps nothing, for tests */
    object None : JsonCache {
        override suspend fun read(key: String, maxAge: Duration?): JsonElement? = null

        override suspend fun write(key: String, value: JsonElement) = Unit

        override suspend fun prune(maxAge: Duration) = Unit
    }
}

/** [JsonCache] in [directory], the Flutter app's `app_flutter/cache` */
class FileJsonCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JsonCache {
    private class Entry(val savedAt: Long, val value: JsonElement)

    private val memory = ConcurrentHashMap<String, Entry>()

    override suspend fun read(key: String, maxAge: Duration?): JsonElement? {
        val entry = readEntry(key) ?: return null
        if (maxAge != null && now() - entry.savedAt > maxAge.inWholeMilliseconds) return null
        return entry.value
    }

    private suspend fun readEntry(key: String): Entry? {
        memory[key]?.let { return it }
        return withContext(io) {
            try {
                val file = fileFor(key)
                if (!file.exists()) return@withContext null
                val decoded = parseJson(file.readText()) as? JsonObject ?: return@withContext null
                Entry(decoded["savedAt"].long(), decoded["value"] ?: JsonNull)
                    .also { memory[key] = it }
            } catch (_: Exception) {
                // A cache miss is always an acceptable answer
                null
            }
        }
    }

    override suspend fun write(key: String, value: JsonElement) {
        val savedAt = now()
        memory[key] = Entry(savedAt, value)
        withContext(io) {
            try {
                val text = buildJsonObject {
                    put("savedAt", savedAt)
                    put("value", value)
                }.toString()
                writeAtomically(fileFor(key), text)
            } catch (_: Exception) {
                // Memory-only is good enough when the disk is not available
            }
        }
    }

    override suspend fun prune(maxAge: Duration) = withContext(io) {
        try {
            val cutoff = now() - maxAge.inWholeMilliseconds
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        } catch (_: Exception) {
            // Pruning is housekeeping: failing to do it changes nothing
        }
        Unit
    }

    private fun fileFor(key: String): File {
        directory.mkdirs()
        return File(directory, "$key.json")
    }
}

/** Write [text] to [file] through a temporary file, so a crash never leaves half */
internal fun writeAtomically(file: File, text: String) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(text)
    if (!temporary.renameTo(file)) {
        temporary.delete()
        file.writeText(text)
    }
}
