package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop

/**
 * The buses on their way to the stops a trip calls at, as the arrivals
 * service reported them.
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
    /**
     * These arrivals, and those of [older] for the stops not asked about
     * again: an answer for one option leaves the rest of the list as it
     * was. Buses already gone drop out of the timing by themselves.
     */
    fun over(older: LiveArrivals?): LiveArrivals {
        if (older == null) return this
        val asked = stops.mapTo(HashSet()) { it.id }
        return LiveArrivals(fetchedAt, stops + older.stops.filter { it.id !in asked }, failed)
    }

    /**
     * The first bus of [line] leaving [board] at or after [after], epoch
     * milliseconds.
     *
     * The routes do not always name a station's platforms as the live
     * service does, so a bus at another platform of the station also
     * counts, but only when it is then seen at [alight]: a bus at the
     * platform across serves the other direction and never gets there.
     */
    fun nextBus(line: String, board: LineStop, alight: LineStop, after: Long): BusArrival? {
        var best: BusArrival? = null
        for (stop in stops) {
            val exact = stop.id == board.stopId
            if (!exact && !sameStation(stop, board)) continue
            for (bus in stop.arrivals) {
                if (bus.line != line || bus.arrivalTime < after) continue
                if (!exact && reachedAt(bus.vehicleId, alight, bus.arrivalTime) == null) continue
                if (best == null || bus.arrivalTime < best.arrivalTime) best = bus
            }
        }
        return best
    }

    /**
     * When [vehicleId] reaches [stop], or any platform of its station,
     * after [after], epoch milliseconds; null when it is not seen there
     */
    fun reachedAt(vehicleId: String, stop: LineStop, after: Long): Long? {
        if (vehicleId.isEmpty()) return null
        var best: Long? = null
        for (seen in stops) {
            if (seen.id != stop.stopId && !sameStation(seen, stop)) continue
            for (bus in seen.arrivals) {
                if (bus.vehicleId != vehicleId || bus.arrivalTime <= after) continue
                if (best == null || bus.arrivalTime < best) best = bus.arrivalTime
            }
        }
        return best
    }

    companion object {
        val NONE = LiveArrivals(fetchedAt = 0, stops = emptyList())

        private fun sameStation(seen: NearbyStop, stop: LineStop): Boolean {
            val station = stationOf(stop.name)
            return station != null && station == stationOf(seen.name)
        }
    }
}
