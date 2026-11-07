package com.yenreh.caligo.domain.entities

import com.yenreh.caligo.domain.metersBetween
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a trip starts or ends */
data class TripPlace(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    /** The device's own position, named by the screen showing it */
    val isDevice: Boolean = false,
)

/** One stretch of a trip, on foot or on a bus */
sealed class TripLeg {
    /** Time spent on the leg, waiting included */
    abstract val total: Duration
}

/** A walk, to a stop or from one */
class WalkLeg(
    val fromLatitude: Double,
    val fromLongitude: Double,
    val toLatitude: Double,
    val toLongitude: Double,
    /** Estimated walking distance, longer than the straight line */
    val meters: Double,
    val duration: Duration,
    /** Stop walked to; null for the last walk, to the destination */
    val toStop: LineStop? = null,
) : TripLeg() {
    override val total: Duration get() = duration

    companion object {
        /**
         * Walking pace, in metres a second: an unhurried 3.6 km/h, with
         * crossings, bags and the heat of Cali
         */
        const val SPEED = 1.0

        /** Sidewalks bend: a walk is longer than the straight line */
        const val DETOUR = 1.3

        /** Whole seconds, rounded half away from zero as Dart's round */
        fun timeFor(straightMeters: Double): Duration = (straightMeters * DETOUR / SPEED).roundToLong().seconds

        /** A walk between two points, estimated from the straight line */
        fun between(
            fromLatitude: Double,
            fromLongitude: Double,
            toLatitude: Double,
            toLongitude: Double,
            toStop: LineStop? = null,
        ): WalkLeg {
            val straight = metersBetween(fromLatitude, fromLongitude, toLatitude, toLongitude)
            return WalkLeg(
                fromLatitude = fromLatitude,
                fromLongitude = fromLongitude,
                toLatitude = toLatitude,
                toLongitude = toLongitude,
                meters = straight * DETOUR,
                duration = timeFor(straight),
                toStop = toStop,
            )
        }
    }
}

/** A ride on one line, from the stop boarded at to the one left at */
class RideLeg(
    val line: String,
    /** Last stop of the line this way, as the bus announces it */
    val towards: String,
    /** Every stop from boarding to getting off, both included */
    val stops: List<LineStop>,
    /**
     * Wait for the bus: a live bus's when timed against one, otherwise
     * what the line usually takes
     */
    val wait: Duration,
    val duration: Duration,
    /**
     * Every line that makes this ride, this one among them, each with its
     * own platforms and stops; empty when only one line does. Kept in the
     * order found, so choosing one does not shuffle the others.
     */
    val variants: List<RideLeg> = emptyList(),
    /** When the bus leaves the stop, epoch milliseconds; null until the trip is timed */
    val departsAt: Long? = null,
    /** Whether [wait] comes from a bus on its way, rather than the usual */
    val liveWait: Boolean = false,
    /**
     * Whether [duration] comes from following that bus to the stop got
     * off at, rather than the estimate from the route
     */
    val liveRide: Boolean = false,
) : TripLeg() {
    /** The same ride timed: against a live bus, or by the usual figures */
    fun timed(wait: Duration, duration: Duration, departsAt: Long, liveWait: Boolean, liveRide: Boolean) = RideLeg(
        line = line,
        towards = towards,
        stops = stops,
        wait = wait,
        duration = duration,
        variants = variants,
        departsAt = departsAt,
        liveWait = liveWait,
        liveRide = liveRide,
    )

    /** Every line that makes the ride */
    val lines: List<String> get() = if (variants.isEmpty()) listOf(line) else variants.map { it.line }

    private val alone: RideLeg get() = RideLeg(line, towards, stops, wait, duration)

    /** The same ride, also made by [other] and whatever lines make it */
    fun withVariant(other: RideLeg): RideLeg {
        val known = variants.ifEmpty { listOf(alone) }
        val lines = known.mapTo(LinkedHashSet()) { it.line }
        return RideLeg(
            line = line,
            towards = towards,
            stops = stops,
            wait = wait,
            duration = duration,
            variants = known + other.variants.ifEmpty { listOf(other.alone) }.filter { lines.add(it.line) },
        )
    }

    /** The same ride made by [line], one of [lines] */
    fun choose(line: String): RideLeg {
        val chosen = variants.first { it.line == line }
        return RideLeg(
            line = chosen.line,
            towards = chosen.towards,
            stops = chosen.stops,
            wait = chosen.wait,
            duration = chosen.duration,
            variants = variants,
        )
    }

    val board: LineStop get() = stops.first()
    val alight: LineStop get() = stops.last()

    /** Stops ridden past, the one got off at included */
    val stopCount: Int get() = stops.size - 1

    override val total: Duration get() = wait + duration
}

/** A way to get from one place to another */
class TripOption(
    val legs: List<TripLeg>,
    /** When the trip was timed from, epoch milliseconds; null until it is */
    val startsAt: Long? = null,
) {
    /** The rides, in order */
    val rides: List<RideLeg> = legs.filterIsInstance<RideLeg>()

    val duration: Duration = legs.fold(Duration.ZERO) { sum, leg -> sum + leg.total }

    /** When the trip ends, once timed, epoch milliseconds */
    val arrivesAt: Long? get() = startsAt?.let { it + duration.inWholeMilliseconds }

    /** Every wait and ride timed against a bus on its way */
    val isLive: Boolean get() = rides.all { it.liveWait && it.liveRide }

    val firstRide: RideLeg? get() = rides.firstOrNull()

    val transfers: Int get() = if (rides.isEmpty()) 0 else rides.size - 1

    val walkMeters: Double get() = legs.filterIsInstance<WalkLeg>().fold(0.0) { sum, leg -> sum + leg.meters }

    /**
     * The lines taken, in order: two options on the same lines differ
     * only in where they are boarded, which is not worth a second row
     */
    val signature: String get() = rides.joinToString(">") { it.line }

    /**
     * The same trip with ride [rideIndex] made by [line]: the walks around
     * it follow, since its platforms may differ
     */
    fun choose(rideIndex: Int, line: String): TripOption {
        val rides = rides.toMutableList()
        rides[rideIndex] = rides[rideIndex].choose(line)
        val first = legs.first() as WalkLeg
        val last = legs.last() as WalkLeg
        return through(
            fromLatitude = first.fromLatitude,
            fromLongitude = first.fromLongitude,
            rides = rides,
            toLatitude = last.toLatitude,
            toLongitude = last.toLongitude,
        )
    }

    companion object {
        /**
         * On foot from the origin to the first bus, between buses where they
         * stop apart, and from the last one to the destination
         */
        fun through(
            fromLatitude: Double,
            fromLongitude: Double,
            rides: List<RideLeg>,
            toLatitude: Double,
            toLongitude: Double,
            startsAt: Long? = null,
        ): TripOption {
            val first = rides.first().board
            val legs = mutableListOf<TripLeg>(
                WalkLeg.between(fromLatitude, fromLongitude, first.latitude, first.longitude, toStop = first),
            )
            for ((i, ride) in rides.withIndex()) {
                legs.add(ride)
                if (i == rides.size - 1) break
                val next = rides[i + 1].board
                if (next.stopId != ride.alight.stopId) {
                    legs.add(
                        WalkLeg.between(
                            ride.alight.latitude,
                            ride.alight.longitude,
                            next.latitude,
                            next.longitude,
                            toStop = next,
                        ),
                    )
                }
            }
            val last = rides.last().alight
            legs.add(WalkLeg.between(last.latitude, last.longitude, toLatitude, toLongitude))
            return TripOption(legs, startsAt)
        }
    }
}
