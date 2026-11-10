package com.yenreh.caligo.domain.entities

import kotlin.math.roundToInt

/** A bus expected to arrive at a stop */
data class BusArrival(
    val line: String,
    val destination: String,
    /** Epoch milliseconds */
    val arrivalTime: Long,
    val vehicleId: String,
    /**
     * Stop the bus arrives at. A station spans several platforms, so an
     * area favorite mixes arrivals from more than one stop.
     */
    val stopName: String = "",
) {
    /** Minutes left until arrival, floored at zero */
    fun minutesUntilArrival(now: Long = System.currentTimeMillis()): Int {
        // Dart's Duration.inSeconds truncates toward zero
        val seconds = (arrivalTime - now) / 1000
        return if (seconds <= 0) 0 else (seconds / 60.0).roundToInt()
    }
}

/** A stop found near a location, with its upcoming buses */
data class NearbyStop(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    val arrivals: List<BusArrival> = emptyList(),
    /**
     * Position worked out from several distance readings. Null when the
     * stop was not seen from enough vantage points to place it.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null
}

/**
 * A stop saved by the user.
 *
 * The arrivals API only accepts coordinates, so each favorite keeps the
 * position it was found from: querying a small radius around that anchor
 * brings the stop back from anywhere.
 */
data class FavoriteStop(
    val id: String,
    /** Name the service reports for the stop */
    val name: String,
    /** Label the user gave it, shown instead of the real name */
    val customName: String? = null,
    val anchorLatitude: Double,
    val anchorLongitude: Double,
    val position: Int = 0,
    /**
     * Stop to follow. When null the favorite covers an area and gathers
     * the arrivals of every stop around the anchor, which is what a
     * station with several platforms needs.
     */
    val stopId: String? = null,
    /**
     * How many times in a row the service answered without mentioning
     * this stop. A stop that is retired, renumbered or moved out of range
     * looks exactly like one with no buses coming, so it is counted.
     */
    val missingCount: Int = 0,
    /** Whether the home screen lists it; the stops screen always does */
    val showOnHome: Boolean = true,
    /**
     * Lines to show, by short name; empty shows every line. A busy stop
     * or a station serves many more lines than one person takes.
     */
    val lines: List<String> = emptyList(),
) {
    val isArea: Boolean get() = stopId == null

    /** Missed often enough that something has changed on the service side */
    val looksGone: Boolean get() = missingCount >= MISSING_THRESHOLD

    /** The arrivals worth showing from [arrivals], by [lines] */
    fun pick(arrivals: List<BusArrival>): List<BusArrival> =
        if (lines.isEmpty()) arrivals else arrivals.filter { picks(it.line) }

    /** Whether [line] is worth showing, by [lines] */
    fun picks(line: String): Boolean = lines.isEmpty() || line in lines

    /** What to show as the title */
    val displayName: String
        get() = if (!customName.isNullOrEmpty()) customName else name

    /** The real name, only when a custom one is taking its place */
    val secondaryName: String?
        get() = if (!customName.isNullOrEmpty()) name else null

    companion object {
        const val MISSING_THRESHOLD = 3
    }
}

/** A MIO station from the public catalog */
data class Station(
    val id: Int,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** A line serving a stop */
data class StopLine(val shortName: String, val name: String)
