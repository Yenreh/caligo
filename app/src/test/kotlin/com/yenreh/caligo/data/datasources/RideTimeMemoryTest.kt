package com.yenreh.caligo.data.datasources

import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ride factor cases of test/stops_remote_datasource_test.dart, "Learned per line" */
class RideTimeMemoryTest {
    @Test
    fun aRideFactorMovesAShareOfTheWayToEachReading() = runTest {
        val memory = RideTimeMemory(JsonCache.None)

        memory.observe(mapOf("T31" to 1.5, "A12A" to 3.0))
        val factors = memory.factors()

        assertEquals(1.1, factors.getValue("T31"), 1e-9)
        // Three times the estimate is a bus held at a terminal, not the line
        assertFalse(factors.containsKey("A12A"))
    }

    @Test
    fun theSameRideReadAgainSettlesOnItsReading() = runTest {
        val memory = RideTimeMemory(JsonCache.None)

        repeat(30) { memory.observe(mapOf("T31" to 1.5)) }

        assertEquals(1.5, memory.factors().getValue("T31"), 0.01)
        assertTrue(memory.factors().getValue("T31") <= 1.5)
    }

    // Beyond the Dart test: what is kept, as the Flutter app keeps it

    /** A cache in memory, recording each write */
    private class MemoryCache(initial: Map<String, JsonElement> = emptyMap()) : JsonCache {
        val kept = initial.toMutableMap()
        var writes = 0

        override suspend fun read(key: String, maxAge: Duration?) = kept[key]

        override suspend fun readEntry(key: String, remember: Boolean) = kept[key]?.let { CacheEntry(0, it) }

        override suspend fun write(key: String, value: JsonElement, remember: Boolean) {
            writes++
            kept[key] = value
        }

        override suspend fun prune(maxAge: Duration) = Unit
    }

    @Test
    fun startsFromTheFactorsKeptAndLeavesOutAnythingButNumbers() = runTest {
        val cache = MemoryCache(
            mapOf(
                "line_ride_factors" to JsonObject(
                    mapOf("T31" to JsonPrimitive(1.2), "A1" to JsonPrimitive("fast"), "E21" to JsonPrimitive(1)),
                ),
            ),
        )

        assertEquals(mapOf("T31" to 1.2, "E21" to 1.0), RideTimeMemory(cache).factors())
    }

    @Test
    fun keepsWhatItLearnsAndWritesNothingForReadingsOutOfRange() = runTest {
        val cache = MemoryCache()
        val memory = RideTimeMemory(cache)

        memory.observe(mapOf("A12A" to 0.4))
        assertEquals(0, cache.writes)
        assertNull(cache.kept["line_ride_factors"])

        memory.observe(mapOf("T31" to 2.0))
        assertEquals(1, cache.writes)
        assertEquals(1.2, RideTimeMemory(cache).factors().getValue("T31"), 1e-9)
    }
}
