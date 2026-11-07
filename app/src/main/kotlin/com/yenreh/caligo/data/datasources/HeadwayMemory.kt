package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.json.doubleOrNull
import com.yenreh.caligo.data.json.isNum
import com.yenreh.caligo.data.json.long
import com.yenreh.caligo.domain.entities.NearbyStop
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How often each line comes, learned from the arrivals the app already
 * asks for: the gaps between a line's next buses at a stop.
 *
 * Kept per hour of the day, since a line runs more often at rush hour,
 * and blended over time so one odd reading moves it only a little. The
 * day each line was last seen coming is kept too: the line catalog can
 * leave out a line that runs.
 *
 * Times are epoch milliseconds; hours are the device's, in [zone].
 */
class HeadwayMemory(
    private val cache: JsonCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
) {
    private val lock = Mutex()

    /** Minutes between buses, by line and then by hour of the day */
    private var minutes: MutableMap<String, MutableMap<String, Double>>? = null

    /** Day each line was last seen coming, counted from the epoch */
    private var seen: MutableMap<String, Long>? = null
    private var savedAt: Long? = null
    private var dirty = false

    private fun hourOf(at: Long) = Calendar.getInstance(zone()).apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)

    private suspend fun load(): MutableMap<String, MutableMap<String, Double>> {
        minutes?.let { return it }
        val stored = cache.read(KEY)
        val loaded = LinkedHashMap<String, MutableMap<String, Double>>()
        if (stored is JsonObject) {
            for ((line, hours) in stored) {
                if (hours !is JsonObject) continue
                loaded[line] = LinkedHashMap<String, Double>().apply {
                    for ((hour, value) in hours) if (value.isNum) put(hour, value.doubleOrNull()!!)
                }
            }
        }
        return loaded.also { minutes = it }
    }

    private suspend fun loadSeen(): MutableMap<String, Long> {
        seen?.let { return it }
        val stored = cache.read(SEEN_KEY)
        val loaded = LinkedHashMap<String, Long>()
        if (stored is JsonObject) {
            for ((line, day) in stored) if (day.isNum) loaded[line] = day.long()
        }
        return loaded.also { seen = it }
    }

    /** Learn from the buses on their way to [stops], seen at [at] */
    suspend fun observe(stops: List<NearbyStop>, at: Long) = lock.withLock {
        val memory = load()
        val seen = loadSeen()
        val hour = "${hourOf(at)}"
        val today = day(at)

        for (stop in stops) {
            val byLine = LinkedHashMap<String, MutableList<Long>>()
            for (bus in stop.arrivals) {
                byLine.getOrPut(bus.line) { mutableListOf() }.add(bus.arrivalTime)
                if (seen[bus.line] != today) {
                    seen[bus.line] = today
                    dirty = true
                }
            }
            for ((line, times) in byLine) {
                if (times.size < 2) continue
                times.sort()
                // Whole seconds, as Dart's Duration.inSeconds counts them
                val gaps = (1 until times.size)
                    .map { ((times[it] - times[it - 1]) / 1000) / 60.0 }
                    .filter { it in SHORTEST_MINUTES..LONGEST_MINUTES }
                    .sorted()
                if (gaps.isEmpty()) continue

                val reading = gaps[gaps.size / 2]
                val hours = memory.getOrPut(line) { LinkedHashMap() }
                val known = hours[hour]
                hours[hour] = if (known == null) reading else known * (1 - BLEND) + reading * BLEND
                dirty = true
            }
        }

        val last = savedAt
        val time = now()
        if (dirty && (last == null || time - last > SAVE_EVERY.inWholeMilliseconds)) {
            dirty = false
            savedAt = time
            cache.write(
                KEY,
                JsonObject(memory.mapValues { (_, hours) -> JsonObject(hours.mapValues { (_, m) -> JsonPrimitive(m) }) }),
            )
            cache.write(SEEN_KEY, JsonObject(seen.mapValues { (_, day) -> JsonPrimitive(day) }))
        }
    }

    /**
     * Time between buses of each line known for the hour of [at]: that
     * hour's when seen, otherwise the nearest hour seen within two
     */
    suspend fun at(at: Long): Map<String, Duration> = lock.withLock {
        val memory = load()
        val hour = hourOf(at)
        buildMap {
            for ((line, hours) in memory) {
                for (offset in OFFSETS) {
                    val minutes = hours["${(hour + offset).mod(24)}"] ?: continue
                    put(line, (minutes * 60).roundToLong().seconds)
                    break
                }
            }
        }
    }

    /** Lines seen coming to some stop on the day of [since] or later */
    suspend fun seenSince(since: Long): Set<String> = lock.withLock {
        val from = day(since)
        loadSeen().filterValues { it >= from }.keys.toSet()
    }

    private companion object {
        const val KEY = "line_headways"
        const val SEEN_KEY = "lines_seen"

        /** Weight of each new reading against what was known */
        const val BLEND = 0.3

        /**
         * Gaps outside these are two buses bunched together or a line that
         * is closing for the night, not how often it runs
         */
        const val SHORTEST_MINUTES = 1.5
        const val LONGEST_MINUTES = 60.0

        /** Saved at most this often; readings in between wait in memory */
        val SAVE_EVERY = 5.minutes

        /** The hour itself, then the nearest ones, an hour and two away */
        val OFFSETS = listOf(0, -1, 1, -2, 2)

        const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        fun day(at: Long) = at / DAY_MILLIS
    }
}
