package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.ArrivalPhase
import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.waits.WaitEngine
import kotlin.time.Duration.Companion.minutes

/**
 * The buses at the stops of the ride waited for, at leg [ride], as the
 * trip last heard of them, with no request of their own: the arrivals it
 * asked for the ride, and the answers its waits get, which list every
 * bus at their stop, not only the one awaited. They fade as a list of
 * arrivals does, so the rows count down between answers.
 */
data class RideBuses(val ride: Int, val fading: FadingArrivals = FadingArrivals.EMPTY) {
    /** With [live], the arrivals asked for the ride, at the stops of [candidates] they answered */
    fun heard(live: LiveArrivals, candidates: List<RideLeg>): RideBuses {
        var next = fading
        for (board in candidates.map { it.board }.distinctBy { it.stopId }) {
            val at = live.answeredAt[board.stopId] ?: continue
            next = next.fed(board.stopId, live.stops.firstOrNull { it.id == board.stopId }?.arrivals.orEmpty(), at)
        }
        return copy(fading = next)
    }

    /**
     * With [answer], asked around [point], at the stops of [candidates] it
     * lists, and those it reaches yet leaves out, which have no bus
     */
    fun heard(point: AskPoint, answer: WaitAnswer, candidates: List<RideLeg>): RideBuses {
        var next = fading
        for (board in candidates.map { it.board }.distinctBy { it.stopId }) {
            val listed = answer.stops.firstOrNull { it.id == board.stopId }
            if (listed == null && !WaitEngine.covers(point, AskPoint(board.latitude, board.longitude, placed = true))) continue
            next = next.fed(board.stopId, listed?.arrivals.orEmpty(), answer.fetchedAt)
        }
        return copy(fading = next)
    }

    /**
     * Each line of [journey]'s ride waited for, with its next [MAX_TIMES]
     * buses at its stop at [now]: those it can no longer make greyed, by
     * the rule the trip awaits a bus by, a bus due before the rider is at
     * the stop on foot, or there and gone by then
     */
    fun lines(journey: Journey, now: Long): List<LineTimes> {
        if (journey.pendingRide != ride) return emptyList()
        val walk = (journey.phase(now) as? JourneyPhase.Walking)?.millis ?: 0
        val from = now + walk
        val rows = fading.rows(now)
        return journey.pendingCandidates.map { candidate ->
            val stop = candidate.board.stopId
            val answered = fading.lastAnswerFor(stop)
            val estimated = answered != null && now - answered > STALE_MS
            val times = rows
                .filter { it.stopId == stop && it.bus.line == candidate.line && it.phase !is ArrivalPhase.Passed }
                .take(MAX_TIMES)
                .map { row ->
                    val atStop = row.phase == ArrivalPhase.AtStop
                    val reachable = if (atStop) from < row.bus.arrivalTime + AT_STOP_FOR_MS else row.bus.arrivalTime >= from
                    LineTime(Trip.minutesOf(row.bus.arrivalTime - now), atStop, reachable, estimated)
                }
            LineTimes(candidate.line, times, heard = answered != null)
        }
    }

    companion object {
        /** Buses shown per line */
        const val MAX_TIMES = 3

        /** An answer this old gives times that read "~": the bus may have moved since */
        val STALE = 2.minutes

        private val STALE_MS = STALE.inWholeMilliseconds
        private val AT_STOP_FOR_MS = FadingArrivals.AT_STOP_FOR.inWholeMilliseconds
    }
}

/** A bus of a line at its stop: in [minutes], or [atStop]; greyed when not [reachable]; "~" when [estimated] */
data class LineTime(val minutes: Int, val atStop: Boolean, val reachable: Boolean, val estimated: Boolean)

/** A line of the ride waited for and its next buses at its stop; none listed once [heard] means none coming soon */
data class LineTimes(val line: String, val times: List<LineTime>, val heard: Boolean)
