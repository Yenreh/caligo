package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyLeg
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.TripSource
import com.yenreh.caligo.domain.typicalWait

/** When a trip gets where it goes, epoch milliseconds; [estimated] when some part of it is not live */
data class TripArrival(val at: Long, val estimated: Boolean) {
    companion object {
        /**
         * When [journey] arrives, as reckoned at [now]: a planner trip at
         * its destination, a single ride at its stop to get off at; null
         * for a ride followed with no stop.
         *
         * What is left of the leg on now, then each leg after it timed as
         * the planner times an option, its departures and rides as the
         * planner gave them while their bus can still be caught: the ride
         * waited for leaves with the bus the trip awaits, [awaited], the
         * chosen line's, else the soonest it can catch; a bus missed, or a
         * line chosen the planner did not time, waits as long as its line
         * usually does. Estimated wherever the planner's was, a ride timed
         * by the usual figures, or the ride on now by its route alone.
         */
        fun of(journey: Journey, now: Long, awaited: List<AwaitedLine> = emptyList()): TripArrival? {
            val phase = journey.phase(now)
            if (!journey.planned) {
                val trip = (phase as? JourneyPhase.Riding)?.trip ?: return null
                if (trip.plan.alight == null) return null
                return TripArrival(now + trip.millisTo(trip.plan.target, now), trip.progress(now).source == TripSource.ESTIMATE)
            }
            var at: Long
            var estimated = false
            val from: Int
            when (phase) {
                is JourneyPhase.Riding -> {
                    val trip = phase.trip
                    at = now + trip.millisTo(trip.plan.target, now)
                    estimated = trip.progress(now).source == TripSource.ESTIMATE
                    from = journey.index + 1
                }
                is JourneyPhase.Walking -> {
                    at = now + phase.millis
                    from = journey.index + 1
                }
                is JourneyPhase.Waiting -> {
                    at = now
                    from = journey.legs.indexOf(phase.leg).coerceAtLeast(journey.index)
                }
            }
            for (i in from until journey.legs.size) {
                when (val leg = journey.legs[i]) {
                    is JourneyLeg.Walk -> at += leg.walk.duration.inWholeMilliseconds
                    is JourneyLeg.Ride -> {
                        val ride = ride(journey, i, leg, at, if (i == journey.pendingRide) awaited else emptyList())
                        at = ride.first
                        estimated = estimated || ride.second
                    }
                }
            }
            return TripArrival(at, estimated)
        }

        /** When ride [index] reaches where it gets off, boarded from [at], and whether that is estimated */
        private fun ride(journey: Journey, index: Int, leg: JourneyLeg.Ride, at: Long, awaited: List<AwaitedLine>): Pair<Long, Boolean> {
            val ride = leg.ride
            val chosen = journey.chosenLines[index]
            val lines = chosen?.let { listOf(it) } ?: ride.lines
            var best: Pair<Long, Boolean>? = null
            for (line in lines) {
                val variant = if (ride.variants.isEmpty()) ride else ride.choose(line)
                val planned = line == ride.line
                // The planner's own timing, for the line it timed
                val duration = (if (planned) ride.duration else variant.duration).inWholeMilliseconds
                val bus = awaited.filter { it.line == line && !it.asking && it.dueAt(at) >= at }.minOfOrNull { it.dueAt(at) }
                val arrival: Pair<Long, Boolean> = when {
                    bus != null -> bus + duration to !(planned && ride.liveRide)
                    planned && ride.departsAt != null && ride.departsAt >= at -> ride.departsAt + duration to !(ride.liveWait && ride.liveRide)
                    // Not timed by the planner: only when chosen, or with no line it timed
                    planned || chosen != null -> at + typicalWait(line).inWholeMilliseconds + duration to true
                    else -> continue
                }
                if (best == null || arrival.first < best.first) best = arrival
            }
            return best ?: (at + typicalWait(ride.line).inWholeMilliseconds + ride.duration.inWholeMilliseconds to true)
        }
    }
}
