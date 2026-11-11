package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.ArrivalPhase
import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.waits.WaitEngine
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * The buses at the stops of the ride waited for, at leg [ride], as the
 * trip last heard of them, with no request of their own: the arrivals it
 * asked for the ride, and the answers its waits get, which list every
 * bus at their stop, not only the one awaited. They fade as a list of
 * arrivals does, so the rows count down between answers.
 */
data class RideBuses(
    val ride: Int,
    val fading: FadingArrivals = FadingArrivals.EMPTY,
    /**
     * Buses the trip awaits at another platform of a line's station, as
     * the arrivals saw them reach where the ride gets off: shown in their
     * line's row, they alone of that platform, whose other buses may run
     * the other way
     */
    val elsewhere: Set<AwaitedElsewhere> = emptySet(),
) {
    /** The stops whose buses count: where each line boards, and the platforms of the buses awaited elsewhere */
    private fun stops(candidates: List<RideLeg>): List<GeoStop> =
        (candidates.map { GeoStop(it.board.stopId, it.board.latitude, it.board.longitude) } + elsewhere.map { it.stop }).distinctBy { it.id }

    /** With [live], the arrivals asked for the ride, at the stops of [candidates] they answered */
    fun heard(live: LiveArrivals, candidates: List<RideLeg>): RideBuses {
        var next = fading
        for (stop in stops(candidates)) {
            val at = live.answeredAt[stop.id] ?: continue
            next = next.fed(stop.id, live.stops.firstOrNull { it.id == stop.id }?.arrivals.orEmpty(), at)
        }
        return copy(fading = next)
    }

    /**
     * With [answer], asked around [point], at the stops of [candidates] it
     * lists, and those it reaches yet leaves out, which have no bus
     */
    fun heard(point: AskPoint, answer: WaitAnswer, candidates: List<RideLeg>): RideBuses {
        var next = fading
        for (stop in stops(candidates)) {
            val listed = answer.stops.firstOrNull { it.id == stop.id }
            if (listed == null && !WaitEngine.covers(point, AskPoint(stop.latitude, stop.longitude, placed = true))) continue
            next = next.fed(stop.id, listed?.arrivals.orEmpty(), answer.fetchedAt)
        }
        return copy(fading = next)
    }

    /** With [awaited], the buses the trip awaits for the ride: those at another platform than their line's go in its row */
    fun awaiting(awaited: List<AwaitedBus>, candidates: List<RideLeg>, live: LiveArrivals): RideBuses {
        val boards = candidates.associate { it.line to it.board.stopId }
        val others = awaited.mapNotNull { bus ->
            if (boards[bus.bus.line] == bus.stopId) return@mapNotNull null
            val seen = live.stops.firstOrNull { it.id == bus.stopId }
            val place = bus.place ?: seen?.let { s -> s.latitude?.let { lat -> s.longitude?.let { GeoPoint(lat, it) } } } ?: bus.anchor
            AwaitedElsewhere(bus.bus.line, GeoStop(bus.stopId, place.latitude, place.longitude), bus.bus.vehicleId)
        }
        return copy(elsewhere = elsewhere + others).heard(live, candidates)
    }

    /**
     * Each line of [journey]'s ride waited for, with its next [MAX_TIMES]
     * buses at its stop at [now]: those it can no longer make greyed, by
     * the rule the trip awaits a bus by, a bus due before the rider is at
     * the stop on foot, or there and gone by then. The bus the trip
     * [awaited] for a line is always one of them, at whatever platform,
     * whatever these arrivals heard or missed of it.
     */
    fun lines(journey: Journey, now: Long, awaited: List<AwaitedLine> = emptyList()): List<LineTimes> {
        if (journey.pendingRide != ride) return emptyList()
        val walk = (journey.phase(now) as? JourneyPhase.Walking)?.millis ?: 0
        val from = now + walk
        val rows = fading.rows(now)
        return journey.pendingCandidates.map { candidate ->
            val stop = candidate.board.stopId
            val answered = fading.lastAnswerFor(stop)
            val estimated = answered != null && now - answered > STALE_MS
            // The awaited bus of the line, wherever the trip awaits it, is one of its times
            val others = elsewhere.filter { it.line == candidate.line }
            val heard = rows
                .filter { row ->
                    row.bus.line == candidate.line && row.phase !is ArrivalPhase.Passed &&
                        (row.stopId == stop || others.any { it.stop.id == row.stopId && it.vehicleId == row.bus.vehicleId })
                }
                .distinctBy { it.bus.vehicleId.ifEmpty { "${it.stopId}:${it.bus.arrivalTime}" } }
                .map { row -> Due(row.bus.arrivalTime, row.phase == ArrivalPhase.AtStop, estimated, row.bus.vehicleId) }
            val mine = awaited.firstOrNull { it.line == candidate.line && !it.asking }
                ?.let { Due(it.arrivalTime, it.atStop, estimated = false, it.vehicleId) }
            val shown = when {
                mine == null || heard.any { it.isBus(mine) } -> heard.take(MAX_TIMES)
                // Never cut: it takes the place of the latest
                else -> (heard.take(MAX_TIMES - 1) + mine).sortedBy { it.arrivalTime }
            }
            val times = shown.map { due ->
                val reachable = if (due.atStop) from < due.arrivalTime + AT_STOP_FOR_MS else due.arrivalTime >= from
                LineTime(Trip.minutesOf(due.arrivalTime - now), due.atStop, reachable, due.estimated)
            }
            LineTimes(candidate.line, times, heard = answered != null || mine != null)
        }
    }

    /** A bus of a row, by when it is due and, when the arrivals name it, its vehicle */
    private data class Due(val arrivalTime: Long, val atStop: Boolean, val estimated: Boolean, val vehicleId: String) {
        /** The same bus: by its vehicle, or with none named, due in the same minute */
        fun isBus(other: Due): Boolean = if (vehicleId.isNotEmpty() && other.vehicleId.isNotEmpty()) {
            vehicleId == other.vehicleId
        } else {
            abs(arrivalTime - other.arrivalTime) < SAME_TIME_MS
        }
    }

    companion object {
        /** Two buses of a line due this close, with no vehicle named, are taken as one */
        private const val SAME_TIME_MS = 60_000L

        /** Buses shown per line */
        const val MAX_TIMES = 3

        /** An answer this old gives times that read "~": the bus may have moved since */
        val STALE = 2.minutes

        private val STALE_MS = STALE.inWholeMilliseconds
        private val AT_STOP_FOR_MS = FadingArrivals.AT_STOP_FOR.inWholeMilliseconds
    }
}

/** A stop with where it is */
data class GeoStop(val id: String, val latitude: Double, val longitude: Double)

/** A bus of [line] the trip awaits at [stop], another platform of its station than the one its line boards at */
data class AwaitedElsewhere(val line: String, val stop: GeoStop, val vehicleId: String)

/** A bus of a line at its stop: in [minutes], or [atStop]; greyed when not [reachable]; "~" when [estimated] */
data class LineTime(val minutes: Int, val atStop: Boolean, val reachable: Boolean, val estimated: Boolean)

/** A line of the ride waited for and its next buses at its stop; none listed once [heard] means none coming soon */
data class LineTimes(val line: String, val times: List<LineTime>, val heard: Boolean)
