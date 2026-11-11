package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The ride the rider took, as the wait's "I took it" knew it: its line,
 * where it was going and the stop it was boarded at. [vehicleId] is the
 * arrivals' id, kept but never matched: it is not the bus number.
 */
data class TookRide(
    val line: String,
    val destination: String,
    val stopId: String,
    /** As the wait named the stop */
    val stopName: String,
    val vehicleId: String,
    /** The wait it ends, by its trip: the time its row gave when the wait started */
    val trip: Long,
) {
    val waitKey: WaitKey get() = WaitKey(stopId, vehicleId, trip)
}

/**
 * One ride to follow: one direction of a line, the stop it was boarded
 * at and the one to get off at, by index along [stops]; [alight] null
 * just follows the ride to the line's last stop.
 */
data class TripPlan(
    val took: TookRide,
    val direction: Int,
    /** The direction's stops, in route order */
    val stops: List<LineStop>,
    val board: Int,
    val alight: Int? = null,
    /** How the line's rides run against its route's estimate, as the planner learned it */
    val factor: Double = 1.0,
) {
    val line: String get() = took.line

    /** Where the ride ends: the stop to get off at, or the line's last */
    val target: Int get() = alight ?: stops.lastIndex

    /** The stops after the boarding one, where the rider may get off */
    val ahead: IntRange get() = board + 1..stops.lastIndex

    companion object {
        /**
         * The plan for [took] on [route], both directions of its line, or
         * null when no direction calls at its stop with a stop after it.
         *
         * The direction is the one whose stops include the boarding stop.
         * When both do, as at a stop served both ways, the one whose last
         * stop is named as the bus's destination; failing that, the one
         * with more stops ahead, which the rider is likelier to be riding.
         */
        fun of(took: TookRide, route: List<LineStop>, factor: Double = 1.0): TripPlan? {
            val candidates = route.map { it.direction }.distinct().mapNotNull { direction ->
                val stops = route.filter { it.direction == direction }.sortedBy { it.sequence }
                val board = stops.indexOfFirst { it.stopId == took.stopId }
                if (board < 0 || board == stops.lastIndex) null else TripPlan(took, direction, stops, board, factor = factor)
            }
            if (candidates.size <= 1) return candidates.firstOrNull()
            val named = candidates.filter { sameName(it.stops.last().name, took.destination) }
            if (named.size == 1) return named.single()
            return candidates.maxBy { it.stops.size - it.board }
        }

        /** Whether a stop's name and a destination name one place: "Est. Universidades" and "Universidades" */
        internal fun sameName(stop: String, destination: String): Boolean {
            val a = words(stop)
            val b = words(destination)
            return a.isNotEmpty() && b.isNotEmpty() && (a.containsAll(b) || b.containsAll(a))
        }

        private fun words(name: String): Set<String> =
            Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() && it !in FILLER && !PLATFORM_CODE.matches(it) }
                .toSet()

        private val FILLER = setOf("est", "estacion", "terminal", "de", "del", "la", "el")
        private val PLATFORM_CODE = Regex("[a-e][0-9]+")
    }
}

/** Every way a trip ends; the process going away has no event, and resumes nothing */
enum class TripEnding {
    /** The stop to get off at reached: passed, or stood at a while */
    REACHED,

    /** Finish, on the notification or the screen */
    FINISHED,

    /** The notification swiped away */
    DISMISSED,

    /** The longest a trip lasts: two hours on a ride, three on a planner trip */
    TIME_LIMIT,

    /** The rider off the route for good */
    OFF_ROUTE,

    /** The app's notifications, or the trips' channel, turned off */
    BLOCKED,

    /** Location turned off, or its permission taken back */
    NO_LOCATION,

    /** Another ride started in its place */
    REPLACED,
}

/** What backs a trip's progress at a moment */
enum class TripSource {
    /** A fix of the rider's own, on the route */
    FIX,

    /** The rider's bus, with no fix to go by */
    BUS,

    /** Neither: the route's estimate moves the rider on, and times read "~" */
    ESTIMATE,
}

/** A fix of the device's location, [accuracy] metres either way, taken at [at] */
data class TripFix(val point: GeoPoint, val accuracy: Double, val at: Long) {
    /** Exact enough to place the rider on the route; network fixes off by blocks are not */
    val good: Boolean get() = accuracy <= GOOD_ACCURACY

    companion object {
        const val GOOD_ACCURACY = 100.0
    }
}

/** The rider's bus, followed by its number */
data class FollowedBus(
    val busNumber: String,
    /** Metres along the route where it was last seen */
    val along: Double,
    /** When the answer that last placed it was fetched */
    val seenAt: Long,
    /** Answers in a row that left it out, or placed it off the route */
    val missed: Int = 0,
    /** Answers in a row that placed it past the stop to get off at */
    val pastStop: Int = 0,
)

/**
 * A stop passed on a ride, at [at]; [backed] when a fix or the bus placed
 * the rider within moments of it, not the estimate across a gap
 */
data class StopPass(val index: Int, val at: Long, val backed: Boolean)

/** Where a trip stands at a moment, and what backs it */
data class TripProgress(
    /** Metres along the route */
    val along: Double,
    val source: TripSource,
    /** The stops ahead up to where the ride ends, that one included; 0 once at it */
    val stopsLeft: Int,
    /** The last stop reached */
    val stop: Int,
)

/** What a trip does at a moment */
sealed interface TripStep {
    data object Goes : TripStep

    data class Ends(val ending: TripEnding) : TripStep
}

/**
 * A ride followed: what is known of it, read at a moment. Fed fixes and
 * the line's buses; what it says depends on those alone, never on the
 * clock running.
 *
 * [confirmed] only moves on, by fixes and by the bus, so GPS noise and
 * stops close together never take the rider back. Between them, and
 * without either, the route's estimate moves the rider on from there.
 */
data class Trip(
    val plan: TripPlan,
    val startedAt: Long,
    /** Metres along the route a fix or the bus last put the rider at, or the boarding stop */
    val confirmed: Double,
    val confirmedAt: Long,
    val confirmedBy: TripSource = TripSource.ESTIMATE,
    /** The last good fix on the route, and where it put the rider */
    val lastFix: TripFix? = null,
    val lastFixAlong: Double? = null,
    val bus: FollowedBus? = null,
    /** When good fixes began to fall beyond the corridor; null while on it */
    val offRouteSince: Long? = null,
    /**
     * When a fix of the rider's own put them at the stop to get off at;
     * null while their progress is short of it
     */
    val atStopSince: Long? = null,
    /** The lowest warning level gone out, or that there was no need for: see [warningLevel] */
    val warned: Int = NO_WARNING,
    /** A warning has gone out */
    val alerted: Boolean = false,
    /** The stops passed so far, as the rider's progress was confirmed past each */
    val passes: List<StopPass> = emptyList(),
) {
    val route: TripRoute by lazy(LazyThreadSafetyMode.NONE) { TripRoute(plan.stops) }

    /** Estimated seconds from the first stop to each, with the line's factor */
    private val seconds: DoubleArray by lazy(LazyThreadSafetyMode.NONE) {
        TransitNetwork.routeSeconds(plan.line, plan.stops).also { s -> for (i in s.indices) s[i] *= plan.factor }
    }

    private val targetAlong: Double get() = route.at[plan.target]

    /**
     * Where the stop to get off at is behind the rider: past it; or, when
     * the route given ends there, as a planner's ride does, at it, since
     * nothing past it can be told
     */
    private val passedAlong: Double
        get() = if (plan.target == plan.stops.lastIndex) targetAlong - TripRoute.AT_STOP_METERS else targetAlong + PASSED_METERS

    /** Where the rider is at [now] */
    fun progress(now: Long): TripProgress {
        val fresh = when (confirmedBy) {
            TripSource.FIX -> now - confirmedAt <= FIX_FRESH_MS
            TripSource.BUS -> now - confirmedAt <= BUS_FRESH_MS
            TripSource.ESTIMATE -> false
        }
        val along = if (fresh) confirmed else minOf(estimateFrom(confirmed, (now - confirmedAt) / 1000.0), targetAlong).coerceAtLeast(confirmed)
        val source = if (fresh) confirmedBy else TripSource.ESTIMATE
        return TripProgress(along, source, route.stopsLeft(along, plan.target), route.stopAt(along))
    }

    /** Estimated seconds from [along] to stop [index]; 0 once there */
    fun secondsTo(index: Int, along: Double): Double = (seconds[index] - secondsAt(along)).coerceAtLeast(0.0)

    /** Estimated milliseconds from where the rider is at [now] to stop [index] */
    fun millisTo(index: Int, now: Long): Long = (secondsTo(index, progress(now).along) * 1000).toLong()

    /** Whole minutes to show for [millis], at least 1 while not there */
    fun minutes(millis: Long): Int = minutesOf(millis)

    private fun secondsAt(along: Double): Double {
        val at = route.at
        if (along <= 0) return 0.0
        if (along >= at.last()) return seconds.last()
        var i = 0
        while (i < at.size - 2 && at[i + 1] < along) i++
        val span = at[i + 1] - at[i]
        val t = if (span == 0.0) 0.0 else (along - at[i]) / span
        return seconds[i] + t * (seconds[i + 1] - seconds[i])
    }

    private fun alongAtSeconds(target: Double): Double {
        val at = route.at
        if (target <= 0) return 0.0
        if (target >= seconds.last()) return at.last()
        var i = 0
        while (i < seconds.size - 2 && seconds[i + 1] < target) i++
        val span = seconds[i + 1] - seconds[i]
        val t = if (span == 0.0) 0.0 else (target - seconds[i]) / span
        return at[i] + t * (at[i + 1] - at[i])
    }

    private fun estimateFrom(along: Double, elapsedSeconds: Double): Double =
        alongAtSeconds(secondsAt(along) + elapsedSeconds.coerceAtLeast(0.0))

    /** With [fix]: a good one on the route moves the rider on; good ones off it, for long, end the trip */
    fun fixed(fix: TripFix): Trip {
        if (!fix.good) return this
        // From the rider's own last fix: one of theirs outweighs the bus standing in, or the estimate
        val previous = lastFixAlong ?: route.at[plan.board]
        val anchor = progress(fix.at).along
        return when (val follow = route.follow(previous, fix.point, fix.at - confirmedAt, anchor)) {
            is Follow.On -> {
                // A fix after the bus or the estimate stood in puts the rider where it says, behind them or not
                val moved = if (confirmedBy == TripSource.FIX) maxOf(confirmed, follow.along) else follow.along
                // Taken back short of the stop, a count a bus or the estimate began goes too
                copy(
                    confirmed = moved,
                    lastFix = fix,
                    lastFixAlong = moved,
                    offRouteSince = null,
                    atStopSince = atStop(moved, fix.at, TripSource.FIX),
                    passes = passesTo(moved, fix.at),
                ).confirm(moved, fix.at, TripSource.FIX)
            }
            // On the route somewhere: not off it, but nothing to go by
            Follow.Elsewhere -> copy(offRouteSince = null)
            // Only clearly beyond the corridor counts, the fix's own error taken off
            Follow.Off -> if (route.offset(fix.point) - fix.accuracy > TripRoute.CORRIDOR) {
                copy(offRouteSince = offRouteSince ?: fix.at)
            } else {
                this
            }
        }
    }

    /**
     * With the line's buses as answered at [at]: the rider's bus followed
     * by its number, or matched by position while there is none; without
     * a fresh fix, it moves the rider on
     */
    fun withBuses(buses: List<LineBus>, at: Long): Trip {
        val fixFresh = lastFix != null && at - lastFix.at <= FIX_FRESH_MS
        var followed = bus?.let { known ->
            val seen = buses.firstOrNull { it.busNumber == known.busNumber }
            val moved = seen?.takeIf { it.direction == null || it.direction == plan.direction }
                ?.let { route.follow(known.along, GeoPoint(it.latitude, it.longitude), at - known.seenAt) }
            when (moved) {
                is Follow.On -> known.copy(
                    along = moved.along,
                    seenAt = at,
                    missed = 0,
                    pastStop = if (moved.along >= passedAlong) known.pastStop + 1 else 0,
                )
                else -> known.copy(missed = known.missed + 1, pastStop = 0)
            }
        }
        // The rider is not on a bus that a fix of theirs leaves far behind or ahead
        val riderAlong = if (fixFresh) lastFixAlong else null
        // What it alone moved the rider on went already, with the fix that came after it: see [fixed]
        if (followed != null && (followed.missed >= BUS_LOST_ANSWERS ||
                (riderAlong != null && abs(followed.along - riderAlong) > BUS_DIVERGES_METERS))
        ) {
            followed = null
        }
        if (followed == null) {
            val rider = progress(at).along
            followed = route.match(buses, plan.direction, rider, if (fixFresh) lastFix.point else null)
                ?.let { FollowedBus(it.busNumber, it.along, at) }
        }
        val next = copy(bus = followed)
        // A bus standing in moves the rider on only from where it was just seen
        return if (followed != null && followed.seenAt == at && !fixFresh) {
            next.confirm(followed.along, at, TripSource.BUS)
        } else {
            next
        }
    }

    private fun confirm(along: Double, at: Long, by: TripSource): Trip {
        val moved = maxOf(confirmed, along)
        return copy(
            confirmed = moved,
            confirmedAt = at,
            confirmedBy = by,
            atStopSince = atStop(moved, at, by),
            passes = passesTo(moved, at),
        )
    }

    /**
     * [passes] with the rider confirmed at [along] at [at]: each stop newly
     * passed since [confirmed], backed when the confirmation before came
     * within moments; taken back, those beyond go
     */
    private fun passesTo(along: Double, at: Long): List<StopPass> {
        val from = route.stopAt(confirmed)
        val to = route.stopAt(along)
        if (to < from) return passes.filter { it.index <= to }
        val backed = at - confirmedAt <= PASS_BACKED_MS
        val known = passes.mapTo(HashSet()) { it.index }
        return passes + (from + 1..to).filter { it !in known }.map { StopPass(it, at, backed) }
    }

    /**
     * How the ride really ran against the route's estimate alone, the
     * line's factor left out, as `observeRides` takes it: from the first
     * stop after boarding to the one got off at. Null when most of it, or
     * either end, was timed by the estimate rather than a fix or the bus,
     * or when it is too short to say much.
     */
    fun reading(): Double? {
        val first = plan.board + 1
        val last = plan.target
        if (last <= first) return null
        val start = passes.firstOrNull { it.index == first } ?: return null
        val end = passes.firstOrNull { it.index == last } ?: return null
        val span = passes.filter { it.index in first..last }
        if (!start.backed || !end.backed || span.count { it.backed } * 2 < last - first + 1) return null
        val raw = TransitNetwork.routeSeconds(plan.line, plan.stops)
        val estimate = raw[last] - raw[first]
        if (estimate < READABLE_RIDE.inWholeSeconds) return null
        val took = (end.at - start.at) / 1000.0
        return if (took > 0) took / estimate else null
    }

    /**
     * When the rider counts as at the stop, at [along] metres by [by]:
     * only a fix of their own starts the count, and progress short of the
     * stop clears it
     */
    private fun atStop(along: Double, at: Long, by: TripSource): Long? = when {
        along < targetAlong - TripRoute.AT_STOP_METERS -> null
        by == TripSource.FIX -> atStopSince ?: at
        else -> atStopSince
    }

    /** Get off at [alight] instead, or just follow the ride when null: the warnings start over */
    fun alightAt(alight: Int?, now: Long): Trip {
        val changed = copy(plan = plan.copy(alight = alight), atStopSince = null)
        val byFix = changed.lastFixAlong?.let { it >= changed.targetAlong - TripRoute.AT_STOP_METERS } == true
        return changed.copy(
            atStopSince = if (byFix) now else null,
            warned = changed.warningLevel(changed.progress(now).stopsLeft) ?: NO_WARNING,
        )
    }

    /** At [now]: whether it goes on, or how it ends */
    fun step(now: Long): TripStep {
        if (now >= startedAt + TIME_LIMIT_MS) return TripStep.Ends(TripEnding.TIME_LIMIT)
        if (confirmed >= passedAlong && passedFor(now)) return TripStep.Ends(TripEnding.REACHED)
        atStopSince?.let { if (now >= it + AT_STOP_FOR_MS) return TripStep.Ends(TripEnding.REACHED) }
        // Only the estimate: once it has had the rider there a good while
        val reachedByEstimate = confirmedAt + (secondsTo(plan.target, confirmed) * 1000).toLong()
        if (progress(now).source == TripSource.ESTIMATE && now >= reachedByEstimate + ESTIMATE_GRACE_MS) {
            return TripStep.Ends(TripEnding.REACHED)
        }
        offRouteSince?.let { if (now >= it + OFF_ROUTE_FOR_MS) return TripStep.Ends(TripEnding.OFF_ROUTE) }
        return TripStep.Goes
    }

    /**
     * Whether the stop to get off at is behind the rider for sure: a fix of
     * their own at it or past it; or else the bus they are on past it in
     * two answers in a row, and at least half the route's estimate from
     * boarding gone since the start, so a bus a little ahead taken for
     * theirs, or the next of a bunch, does not end the ride early
     */
    private fun passedFor(now: Long): Boolean {
        val fix = lastFixAlong
        if (fix != null && fix >= targetAlong - TripRoute.AT_STOP_METERS) return true
        val bus = bus ?: return false
        val half = secondsTo(plan.target, route.at[plan.board]) * 1000 / 2
        return bus.pastStop >= BUS_PAST_ANSWERS && now - startedAt >= half
    }

    /**
     * The warning [stopsLeft] calls for: [WARN_TWO_STOPS] as the stop to
     * get off at is two stops away, [WARN_NEXT] once it is next or
     * reached. None just following the ride, nor further away.
     */
    fun warningLevel(stopsLeft: Int): Int? = when {
        plan.alight == null -> null
        stopsLeft == 2 -> WARN_TWO_STOPS
        stopsLeft <= 1 -> WARN_NEXT
        else -> null
    }

    /** The warning due at [now] that has not gone out, if any */
    fun warningDue(now: Long): Int? = warningLevel(progress(now).stopsLeft)?.takeIf { it < warned }

    /** [now]'s warning marked as gone out */
    fun warnedAt(now: Long): Trip = warningDue(now)?.let { copy(warned = it, alerted = true) } ?: this

    companion object {
        /** Whole minutes to show for [millis], at least 1 while not there */
        fun minutesOf(millis: Long): Int = if (millis <= 0) 0 else (millis / 60_000.0).roundToInt().coerceAtLeast(1)

        /** Follow [plan] from [now], the rider at the boarding stop */
        fun start(plan: TripPlan, now: Long): Trip {
            val boarded = Trip(plan, now, confirmed = 0.0, confirmedAt = now).let { it.copy(confirmed = it.route.at[plan.board]) }
            // A stop chosen close by needs no warning for what is already due
            return boarded.copy(warned = boarded.warningLevel(boarded.progress(now).stopsLeft) ?: NO_WARNING)
        }

        /** Warning levels: lower is closer */
        const val WARN_NEXT = 1
        const val WARN_TWO_STOPS = 2
        const val NO_WARNING = 3

        /** How long a trip lasts at most */
        val TIME_LIMIT = 2.hours

        /** A fix this old still places the rider; past it, the estimate takes over */
        val FIX_FRESH = 40.seconds

        /** The bus's position, answered every 30 s and late itself, holds a little longer */
        val BUS_FRESH = 75.seconds

        /** Passed this far, the stop to get off at is behind */
        const val PASSED_METERS = 60.0

        /** Stood at the stop to get off at this long, the rider got off */
        val AT_STOP_FOR = 2.minutes

        /** The estimate alone ends the trip this long after it reached the stop */
        val ESTIMATE_GRACE = 10.minutes

        /** Good fixes beyond the corridor this long: the rider left the route */
        val OFF_ROUTE_FOR = 3.minutes

        /** Answers in a row without the rider's bus before another is matched */
        const val BUS_LOST_ANSWERS = 3

        /** Answers in a row a bus must be past the stop to get off at, as the only sign of it */
        const val BUS_PAST_ANSWERS = 2

        /** A stop passed this soon after the confirmation before counts as timed, not estimated */
        val PASS_BACKED = 90.seconds

        /** A bus this far along from the rider's own fix is not theirs */
        const val BUS_DIVERGES_METERS = 600.0

        private val TIME_LIMIT_MS = TIME_LIMIT.inWholeMilliseconds
        private val FIX_FRESH_MS = FIX_FRESH.inWholeMilliseconds
        private val BUS_FRESH_MS = BUS_FRESH.inWholeMilliseconds
        private val AT_STOP_FOR_MS = AT_STOP_FOR.inWholeMilliseconds
        private val ESTIMATE_GRACE_MS = ESTIMATE_GRACE.inWholeMilliseconds
        private val OFF_ROUTE_FOR_MS = OFF_ROUTE_FOR.inWholeMilliseconds
        private val PASS_BACKED_MS = PASS_BACKED.inWholeMilliseconds
    }
}
