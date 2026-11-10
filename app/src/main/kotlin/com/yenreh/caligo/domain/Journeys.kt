package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.entities.WalkLeg
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One leg of a journey, as the planner gave it */
sealed interface JourneyLeg {
    /** On foot to [toName]: a stop to board at, or the destination when the walk has no stop */
    data class Walk(val walk: WalkLeg, val toName: String) : JourneyLeg {
        val to: GeoPoint get() = GeoPoint(walk.toLatitude, walk.toLongitude)
        val toStop: Boolean get() = walk.toStop != null
    }

    /** A ride, made by any of its lines */
    data class Ride(val ride: RideLeg) : JourneyLeg {
        /** Each line that makes it, with its own stops and platforms */
        val candidates: List<RideLeg> get() = if (ride.variants.isEmpty()) listOf(ride) else ride.lines.map { ride.choose(it) }
    }
}

/** Where a journey stands at a moment */
sealed interface JourneyPhase {
    /** On foot: [meters] and [millis] left, [estimated] without a fresh fix to go by */
    data class Walking(val leg: JourneyLeg.Walk, val meters: Double, val millis: Long, val estimated: Boolean) : JourneyPhase

    /** At the stop, or walked there by the estimate, for any bus of [leg] */
    data class Waiting(val leg: JourneyLeg.Ride) : JourneyPhase

    /** On the bus */
    data class Riding(val trip: Trip) : JourneyPhase
}

/** What a journey does at a moment, and the rides it has just finished, for the planner to learn from */
sealed interface JourneyStep {
    data class Goes(val journey: Journey, val readings: Map<String, Double> = emptyMap()) : JourneyStep

    data class Ends(val ending: TripEnding, val readings: Map<String, Double> = emptyMap()) : JourneyStep
}

/**
 * A whole trip followed leg by leg, read at a moment: walks, rides and the
 * changes between them, as the planner's option has them, or a single
 * ride taken from "I took it". On a walk, the rider's fixes measure what
 * is left on foot; for a ride not yet taken, whichever bus of its lines
 * the rider takes is followed, told by "I took it" on its wait or by the
 * rider leaving the stop at a bus's pace beside one; on the bus, [ride]
 * follows it as a single ride does. Pure, as [Trip].
 */
data class Journey(
    val startedAt: Long,
    val legs: List<JourneyLeg>,
    /** The leg on now */
    val index: Int,
    /** When the leg on now began */
    val legStartedAt: Long,
    /** How each line's rides run against their routes, as the planner learned it */
    val factors: Map<String, Double> = emptyMap(),
    /** Following the bus of the ride at [index], once taken */
    val ride: Trip? = null,
    /** The last good fix, and the one before it, to tell a walk from a bus */
    val fix: TripFix? = null,
    val previousFix: TripFix? = null,
    /** The waits started for the ride at [awaitedFor] */
    val awaited: List<WaitKey> = emptyList(),
    val awaitedFor: Int? = null,
    /** When those waits were last asked for */
    val awaitedAt: Long? = null,
    /** From the planner: whole, with walks; a single ride from "I took it" has its stop to get off at chosen */
    val planned: Boolean = true,
) {
    val leg: JourneyLeg get() = legs[index]

    /** The ride the journey waits to be taken: the one on now, or the one the walk on now leads to */
    val pendingRide: Int?
        get() = when {
            ride != null -> null
            leg is JourneyLeg.Ride -> index
            index + 1 < legs.size && legs[index + 1] is JourneyLeg.Ride -> index + 1
            else -> null
        }

    /** The legs still to come after the one on now */
    val ahead: List<JourneyLeg> get() = legs.drop(index + 1)

    /** Where the journey stands at [now] */
    fun phase(now: Long): JourneyPhase {
        ride?.let { return JourneyPhase.Riding(it) }
        return when (val on = leg) {
            is JourneyLeg.Ride -> JourneyPhase.Waiting(on)
            is JourneyLeg.Walk -> {
                val fresh = fix?.takeIf { now - it.at <= FIX_FRESH_MS && it.at >= legStartedAt }
                if (fresh != null) {
                    val meters = metersBetween(fresh.point.latitude, fresh.point.longitude, on.to.latitude, on.to.longitude) * WalkLeg.DETOUR
                    JourneyPhase.Walking(on, meters, (meters / WalkLeg.SPEED * 1000).toLong(), estimated = false)
                } else {
                    val total = on.walk.duration.inWholeMilliseconds
                    val left = (total - (now - legStartedAt)).coerceAtLeast(0)
                    // Walked there by the estimate: waiting at the stop, as far as the app can tell
                    val next = legs.getOrNull(index + 1)
                    if (left == 0L && on.toStop && next is JourneyLeg.Ride) return JourneyPhase.Waiting(next)
                    val share = if (total > 0) left.toDouble() / total else 0.0
                    JourneyPhase.Walking(on, on.walk.meters * share, left, estimated = true)
                }
            }
        }
    }

    /** With [fix]: a walk measured, a ride followed */
    fun fixed(fix: TripFix): Journey {
        if (!fix.good) return this
        val moved = copy(fix = fix, previousFix = this.fix, ride = ride?.fixed(fix))
        val on = moved.leg
        if (moved.ride == null && on is JourneyLeg.Walk && on.toStop) {
            val meters = metersBetween(fix.point.latitude, fix.point.longitude, on.to.latitude, on.to.longitude)
            if (meters <= ARRIVED_METERS + minOf(fix.accuracy, ARRIVED_ERROR_METERS)) return moved.next(fix.at)
        }
        return moved
    }

    /**
     * With [line]'s buses as answered at [at]: the ride followed, or, for
     * a ride not yet taken, the rider leaving its stop on one of them
     */
    fun withBuses(line: String, buses: List<LineBus>, at: Long): Journey {
        ride?.let { return if (it.plan.line == line) copy(ride = it.withBuses(buses, at)) else this }
        val pending = pendingRide ?: return this
        val candidate = (legs[pending] as JourneyLeg.Ride).candidates.firstOrNull { it.line == line } ?: return this
        val fix = fix?.takeIf { at - it.at <= FIX_FRESH_MS } ?: return this
        val before = previousFix ?: return this
        val gap = fix.at - before.at
        if (gap <= 0 || gap > SPEED_GAP_MS) return this
        val speed = metersBetween(before.point.latitude, before.point.longitude, fix.point.latitude, fix.point.longitude) / (gap / 1000.0)
        if (speed < BUS_PACE) return this
        val route = TripRoute(candidate.stops)
        val along = (route.follow(0.0, fix.point, fix.at - legStartedAt) as? Follow.On)?.along ?: return this
        if (along < LEFT_STOP_METERS) return this
        route.match(buses, candidate.board.direction, along, fix.point) ?: return this
        // Boarded about as the fix before was taken
        return took(line, before.at, pending).let { taken -> taken.copy(ride = taken.ride?.fixed(fix)?.withBuses(buses, at)) }
    }

    /**
     * The ride waited for taken on [line], at [at]: "I took it" on its
     * wait, or the rider seen leaving on it. As it was when no ride waits
     * for that line.
     */
    fun took(line: String, at: Long, rideIndex: Int? = pendingRide): Journey {
        val r = rideIndex ?: return this
        val leg = legs[r] as JourneyLeg.Ride
        val chosen = leg.candidates.firstOrNull { it.line == line } ?: return this
        val board = chosen.board
        val took = TookRide(line, chosen.towards, board.stopId, board.name, vehicleId = "", trip = at)
        val plan = TripPlan(took, board.direction, chosen.stops, board = 0, alight = chosen.stops.lastIndex, factor = factors[line] ?: 1.0)
        return copy(index = r, legStartedAt = at, ride = Trip.start(plan, at))
    }

    /**
     * Whether a bus of [line] taken at [stopId] is the ride waited for: one
     * of its lines, at its stop or one of the waits it started
     */
    fun awaits(line: String, stopId: String): Boolean {
        val r = pendingRide ?: return false
        if (awaitedFor == r && awaited.any { it.stopId == stopId }) return (legs[r] as JourneyLeg.Ride).candidates.any { it.line == line }
        return (legs[r] as JourneyLeg.Ride).candidates.any { it.line == line && it.board.stopId == stopId }
    }

    /** The waits started for ride [rideIndex], at [at] */
    fun awaiting(rideIndex: Int, keys: List<WaitKey>, at: Long): Journey = copy(awaited = keys, awaitedFor = rideIndex, awaitedAt = at)

    /** On to the next leg from [at], or past the last */
    private fun next(at: Long): Journey = copy(index = index + 1, legStartedAt = at, ride = null)

    /** At [now]: whether it goes on, moving to the next leg as one ends, or how it ends */
    fun step(now: Long): JourneyStep {
        if (planned && now >= startedAt + TIME_LIMIT_MS) return JourneyStep.Ends(TripEnding.TIME_LIMIT)
        val riding = ride
        if (riding != null) {
            return when (val step = riding.step(now)) {
                TripStep.Goes -> JourneyStep.Goes(this)
                is TripStep.Ends -> {
                    if (step.ending != TripEnding.REACHED) return JourneyStep.Ends(step.ending)
                    val readings = riding.reading()?.let { mapOf(riding.plan.line to it) }.orEmpty()
                    if (index + 1 >= legs.size) JourneyStep.Ends(TripEnding.REACHED, readings) else JourneyStep.Goes(next(now), readings)
                }
            }
        }
        val on = leg
        if (on is JourneyLeg.Walk && !on.toStop) {
            val here = fix?.takeIf { it.at >= legStartedAt }
                ?.let { metersBetween(it.point.latitude, it.point.longitude, on.to.latitude, on.to.longitude) }
            if (here != null && here <= DESTINATION_METERS) return JourneyStep.Ends(TripEnding.REACHED)
            // With no fix of the walk, the estimate ends it a good while after
            if (fix?.takeIf { it.at >= legStartedAt } == null &&
                now >= legStartedAt + on.walk.duration.inWholeMilliseconds + Trip.ESTIMATE_GRACE.inWholeMilliseconds
            ) {
                return JourneyStep.Ends(TripEnding.REACHED)
            }
        }
        return JourneyStep.Goes(this)
    }

    companion object {
        /** A whole option from the planner, to [destination], from [now] */
        fun planned(option: TripOption, destination: String, now: Long, factors: Map<String, Double> = emptyMap()): Journey {
            val legs = option.legs.map { leg ->
                when (leg) {
                    is WalkLeg -> JourneyLeg.Walk(leg, leg.toStop?.name ?: destination)
                    is RideLeg -> JourneyLeg.Ride(leg)
                }
            }
            // A first walk of a few steps: already at the stop
            val skip = (legs.first() as? JourneyLeg.Walk)?.let { it.toStop && it.walk.meters <= ARRIVED_METERS } == true
            return Journey(now, legs, if (skip) 1 else 0, now, factors)
        }

        /** The single ride "I took it" started, as [plan] has it */
        fun single(plan: TripPlan, now: Long): Journey {
            val stops = plan.stops.subList(plan.board, plan.target + 1)
            val leg = RideLeg(plan.line, plan.took.destination, stops, wait = 0.seconds, duration = 0.seconds)
            return Journey(now, listOf(JourneyLeg.Ride(leg)), 0, now, ride = Trip.start(plan, now), planned = false)
        }

        /** The longest a whole journey lasts */
        val TIME_LIMIT = 3.hours

        /** Within this of the stop walked to, the rider is there */
        const val ARRIVED_METERS = 40.0

        /** Of a fix's own error, at most this much more is allowed */
        const val ARRIVED_ERROR_METERS = 30.0

        /** Within this of the destination, the journey is done */
        const val DESTINATION_METERS = 50.0

        /** Faster than anyone walks: 12.6 km/h */
        const val BUS_PACE = 3.5

        /** Along the route of a line this far past its stop, the rider has left on it */
        const val LEFT_STOP_METERS = 150.0

        /** Two fixes further apart than this tell no pace */
        val SPEED_GAP = 1.minutes

        private val FIX_FRESH_MS = Trip.FIX_FRESH.inWholeMilliseconds
        private val TIME_LIMIT_MS = TIME_LIMIT.inWholeMilliseconds
        private val SPEED_GAP_MS = SPEED_GAP.inWholeMilliseconds
    }
}
