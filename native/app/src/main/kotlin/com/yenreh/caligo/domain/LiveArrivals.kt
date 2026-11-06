package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.NearbyStop

/**
 * The buses on their way to a set of stops, as the arrivals service
 * reported them.
 *
 * The service lists the next five buses of a stop at most, whatever
 * their line, and leaves out a stop with none: a line missing from a
 * busy stop may well be coming, only after those five.
 */
data class LiveArrivals(
    /** When the oldest answer asked for last was fetched, epoch milliseconds */
    val fetchedAt: Long,
    /** Every stop seen, with its buses */
    val stops: List<NearbyStop>,
    /** Whether some area could not be asked about */
    val failed: Boolean = false,
) {
    companion object {
        val NONE = LiveArrivals(fetchedAt = 0, stops = emptyList())
    }
}
