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
    /**
     * When the answer covering each stop was fetched, epoch milliseconds,
     * by stop id: every stop seen, and every stop asked about whose area
     * answered, even when it left the stop out, as it does a stop with no
     * bus coming
     */
    val answeredAt: Map<String, Long> = emptyMap(),
    /**
     * Buses that left the answers near their time and are still at their
     * stop as the trip is timed, by stop id, as [FadingArrivals.atTheStop]
     * tells them
     */
    val atTheStop: Map<String, List<BusArrival>> = emptyMap(),
) {
    /**
     * These arrivals, and those of [older] for the stops not answered
     * again: an answer for one option leaves the rest of the list as it
     * was. Buses already gone drop out of the timing by themselves.
     */
    fun over(older: LiveArrivals?): LiveArrivals {
        if (older == null) return this
        val asked = stops.mapTo(HashSet()) { it.id }
        asked.addAll(answeredAt.keys)
        return LiveArrivals(
            fetchedAt = fetchedAt,
            stops = stops + older.stops.filter { it.id !in asked },
            failed = failed,
            answeredAt = older.answeredAt.filterKeys { it !in asked } + answeredAt,
        )
    }

    /** [fading] fed the answer of each stop answered, at the time it was fetched */
    fun feed(fading: FadingArrivals): FadingArrivals {
        val byId = stops.associateBy { it.id }
        var next = fading
        for ((id, at) in answeredAt) next = next.fed(id, byId[id]?.arrivals.orEmpty(), at)
        return next
    }

    /**
     * The first bus of [line] leaving [board] at or after [after], epoch
     * milliseconds: one on its way, or one [atTheStop] that is still there
     * then, which leaves at once.
     *
     * The routes do not always name a station's platforms as the live
     * service does, so a bus at another platform of the station also
     * counts, but only when it is then seen at [alight]: a bus at the
     * platform across serves the other direction and never gets there.
     */
    fun nextBus(line: String, board: LineStop, alight: LineStop, after: Long): NextBus? {
        var best: NextBus? = null
        fun consider(bus: BusArrival, exact: Boolean, departsAt: Long, atStop: Boolean) {
            if (!exact && reachedAt(bus.vehicleId, alight, bus.arrivalTime) == null) return
            val known = best
            if (known == null || departsAt < known.departsAt) best = NextBus(bus, departsAt, atStop)
        }
        for (stop in stops) {
            val exact = stop.id == board.stopId
            if (!exact && !sameStation(stop.name, board)) continue
            for (bus in stop.arrivals) {
                if (bus.line != line || bus.arrivalTime < after) continue
                consider(bus, exact, bus.arrivalTime, atStop = false)
            }
        }
        for ((stopId, buses) in atTheStop) {
            for (bus in buses) {
                val exact = stopId == board.stopId
                if (!exact && !sameStation(bus.stopName, board)) continue
                // Gone once its minute at the stop is over
                if (bus.line != line || after >= bus.arrivalTime + FadingArrivals.AT_STOP_FOR.inWholeMilliseconds) continue
                // There already, whatever time it last gave: no wait
                consider(bus, exact, after, atStop = true)
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
            if (seen.id != stop.stopId && !sameStation(seen.name, stop)) continue
            for (bus in seen.arrivals) {
                if (bus.vehicleId != vehicleId || bus.arrivalTime <= after) continue
                if (best == null || bus.arrivalTime < best) best = bus.arrivalTime
            }
        }
        return best
    }

    companion object {
        val NONE = LiveArrivals(fetchedAt = 0, stops = emptyList())

        private fun sameStation(seen: String, stop: LineStop): Boolean {
            val station = stationOf(stop.name)
            return station != null && station == stationOf(seen)
        }
    }
}

/** The bus a ride takes: it leaves at [departsAt], at once when it is [atStop] already */
data class NextBus(val bus: BusArrival, val departsAt: Long, val atStop: Boolean)
