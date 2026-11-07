package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.json.doubleOrNull
import com.yenreh.caligo.data.json.isNum
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How each line's rides run against the estimate from its route, learned
 * from buses followed stop to stop by the arrivals service: a line
 * through the busy centre takes longer than its route suggests.
 *
 * Blended over time, so one odd reading moves it only a little and the
 * figure follows the traffic of the last few trips planned.
 */
class RideTimeMemory(private val cache: JsonCache) {
    private val lock = Mutex()
    private var factors: MutableMap<String, Double>? = null

    private suspend fun load(): MutableMap<String, Double> {
        factors?.let { return it }
        val stored = cache.read(KEY)
        val loaded = LinkedHashMap<String, Double>()
        if (stored is JsonObject) {
            for ((line, factor) in stored) if (factor.isNum) loaded[line] = factor.doubleOrNull()!!
        }
        return loaded.also { factors = it }
    }

    /**
     * Learn from [readings], each ride's live time over what its route
     * alone estimates, by line. The same ride read again and again, as an
     * open trip is, only settles the factor on its reading.
     */
    suspend fun observe(readings: Map<String, Double>) = lock.withLock {
        val factors = load()
        var changed = false
        for ((line, reading) in readings) {
            if (reading < LOWEST || reading > HIGHEST) continue
            val known = factors[line] ?: 1.0
            factors[line] = known * (1 - BLEND) + reading * BLEND
            changed = true
        }
        if (changed) cache.write(KEY, JsonObject(factors.mapValues { (_, factor) -> JsonPrimitive(factor) }))
    }

    /** The factor of each line followed so far */
    suspend fun factors(): Map<String, Double> = lock.withLock { LinkedHashMap(load()) }

    private companion object {
        const val KEY = "line_ride_factors"

        /** Weight of each new reading against what was known */
        const val BLEND = 0.2

        /**
         * Readings outside these are a bus held at a terminal or a jump in
         * the service's own estimate, not how the line runs
         */
        const val LOWEST = 0.5
        const val HIGHEST = 2.0
    }
}
