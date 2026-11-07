package com.yenreh.caligo.domain.entities

import java.util.Calendar
import java.util.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** A MIO line, known to the service by its short name */
data class TransitLine(
    /** Number in the line catalog; 0 for a line the catalog leaves out */
    val id: Int,
    val name: String,
)

/** A stop along a line's route */
data class LineStop(
    val stopId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /**
     * Which way the route runs: each line goes out and comes back, and
     * the service numbers the two directions 0 and 1
     */
    val direction: Int,
    /** Place along the route in its direction, from 1 */
    val sequence: Int,
)

/** A bus running on a line, where its GPS last put it */
data class LineBus(
    val busNumber: String,
    val latitude: Double,
    val longitude: Double,
    /** Which of the line's two directions its trip runs; null when the service could not say */
    val direction: Int? = null,
)

/** Hours a line runs, as local times of day */
data class LineHours(val start: Duration, val end: Duration) {
    /**
     * Whether the line runs at [hour]:[minute]. Late lines end past
     * midnight, so an end earlier than the start wraps to the next day.
     */
    fun runsAt(hour: Int, minute: Int): Boolean {
        val time = hour.hours + minute.minutes
        if (end >= start) return time in start..end
        return time >= start || time <= end
    }

    /** Whether the line runs at [epochMillis], in the device's time zone */
    fun runsAt(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val at = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
        return runsAt(at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))
    }
}
