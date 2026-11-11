package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a point falls on a route: [along] metres from its first stop, [offset] metres to one side */
data class RouteSnap(val along: Double, val offset: Double)

/** Where a point puts a rider followed along a route */
sealed interface Follow {
    /** On the route, at [along] metres, never behind where the rider was */
    data class On(val along: Double) : Follow

    /**
     * Within the corridor, but only where the rider could not have got to
     * since: a leg of the route running back close by, or stops close
     * together further on. Neither on nor off: it moves nothing.
     */
    data object Elsewhere : Follow

    /** Beyond the corridor everywhere */
    data object Off : Follow
}

/**
 * One direction of a line as the route a ride follows: the polyline
 * through its stops in order, measured in metres along it. Flat metres
 * around its first stop, as [metersBetween] reckons a city's span.
 *
 * The stops are all the route there is: the service gives no shape, so
 * a street that bends between two stops leaves the bus off the line by
 * up to the [CORRIDOR].
 */
class TripRoute(val stops: List<LineStop>) {
    init {
        require(stops.isNotEmpty()) { "A route needs a stop" }
    }

    private val latitude0 = stops.first().latitude
    private val longitude0 = stops.first().longitude
    private val xScale = METERS_PER_DEGREE * cos(latitude0 * PI / 180)
    private val xs = DoubleArray(stops.size) { (stops[it].longitude - longitude0) * xScale }
    private val ys = DoubleArray(stops.size) { (stops[it].latitude - latitude0) * METERS_PER_DEGREE }

    /** Metres along the route at each stop, from 0 at the first */
    val at: DoubleArray = DoubleArray(stops.size).also {
        for (i in 1 until stops.size) it[i] = it[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    val length: Double get() = at.last()

    /**
     * Every place along the route within [corridor] metres of [point]: the
     * nearest on each stretch that passes close, so a route that runs by
     * twice gives two
     */
    fun snaps(point: GeoPoint, corridor: Double = CORRIDOR): List<RouteSnap> {
        val x = (point.longitude - longitude0) * xScale
        val y = (point.latitude - latitude0) * METERS_PER_DEGREE
        if (stops.size == 1) {
            val offset = hypot(x - xs[0], y - ys[0])
            return if (offset <= corridor) listOf(RouteSnap(0.0, offset)) else emptyList()
        }
        val found = mutableListOf<RouteSnap>()
        for (i in 0 until stops.size - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val span = dx * dx + dy * dy
            val t = if (span == 0.0) 0.0 else (((x - xs[i]) * dx + (y - ys[i]) * dy) / span).coerceIn(0.0, 1.0)
            val offset = hypot(x - (xs[i] + t * dx), y - (ys[i] + t * dy))
            if (offset > corridor) continue
            val snap = RouteSnap(at[i] + t * (at[i + 1] - at[i]), offset)
            // Two stretches meeting at a stop give the same place twice
            val last = found.lastOrNull()
            if (last != null && snap.along - last.along < SAME_PLACE_METERS) {
                if (snap.offset < last.offset) found[found.lastIndex] = snap
            } else {
                found.add(snap)
            }
        }
        return found
    }

    /** How far [point] is from the route at its nearest */
    fun offset(point: GeoPoint): Double = snaps(point, Double.MAX_VALUE).minOf { it.offset }

    /**
     * Where [point] puts a rider last at [previous] metres along: of the
     * places within the corridor, never behind [previous], those the
     * rider could have reached from [anchor], where they were thought to
     * be [elapsed] milliseconds ago, and of those the closest to it. A fix
     * a little behind, as GPS noise puts one, holds the rider where they
     * were; a stop further on that the fix is as close to does not draw
     * them ahead.
     */
    fun follow(previous: Double, point: GeoPoint, elapsed: Long, anchor: Double = previous, corridor: Double = CORRIDOR): Follow {
        val snaps = snaps(point, corridor)
        if (snaps.isEmpty()) return Follow.Off
        val from = maxOf(previous, anchor)
        val reach = from + MAX_SPEED * elapsed.coerceAtLeast(0) / 1000.0 + REACH_MARGIN_METERS
        val best = snaps
            .filter { it.along >= previous - NOISE_METERS && it.along <= reach }
            .minByOrNull { it.offset + DRIFT_WEIGHT * abs(it.along - from) }
            ?: return Follow.Elsewhere
        return Follow.On(maxOf(previous, best.along))
    }

    /** The last stop at or behind [along], a stop counting as reached [AT_STOP_METERS] before it */
    fun stopAt(along: Double): Int {
        var index = 0
        for (i in at.indices) if (at[i] <= along + AT_STOP_METERS) index = i else break
        return index
    }

    /** The stops still ahead of [along] up to [target], [target] included; 0 once at it */
    fun stopsLeft(along: Double, target: Int): Int = (target - stopAt(along)).coerceAtLeast(0)

    private val latitudes = DoubleArray(stops.size) { stops[it].latitude }
    private val longitudes = DoubleArray(stops.size) { stops[it].longitude }

    /** The point [along] metres along the route, for a map to draw the rider at */
    fun pointAt(along: Double): GeoPoint {
        if (stops.size == 1) return GeoPoint(latitudes[0], longitudes[0])
        return GeoPoint(interpolate(at, latitudes, along), interpolate(at, longitudes, along))
    }

    /**
     * The rider's bus among [buses], by position alone: the arrivals' ids
     * are not the bus numbers. Of those on the route going [direction],
     * or whose direction is unknown, the one nearest along it to [rider]
     * metres, within reach of a bus the rider is on: a little behind, as
     * its position lags, or ahead. With [riderPoint], a fix of the rider's
     * own, it must also be that close to them.
     */
    fun match(buses: List<LineBus>, direction: Int, rider: Double, riderPoint: GeoPoint? = null): MatchedBus? {
        var best: MatchedBus? = null
        for (bus in buses) {
            if (bus.direction != null && bus.direction != direction) continue
            val point = GeoPoint(bus.latitude, bus.longitude)
            if (riderPoint != null &&
                metersBetween(point.latitude, point.longitude, riderPoint.latitude, riderPoint.longitude) > MATCH_RADIUS_METERS
            ) {
                continue
            }
            val snap = snaps(point)
                .filter { it.along >= rider - MATCH_BEHIND_METERS && it.along <= rider + MATCH_AHEAD_METERS }
                .minByOrNull { abs(it.along - rider) } ?: continue
            if (best == null || abs(snap.along - rider) < abs(best.along - rider)) best = MatchedBus(bus.busNumber, snap.along)
        }
        return best
    }

    companion object {
        private const val METERS_PER_DEGREE = 111320.0

        /**
         * How far off the line through the stops a bus on the route can be:
         * streets bend between stops, and a fix is off by tens of metres
         */
        const val CORRIDOR = 150.0

        /** A stop is reached this far before it: a bus stops along its length */
        const val AT_STOP_METERS = 40.0

        /** How far behind a fix may fall without moving the rider back: noise */
        private const val NOISE_METERS = 60.0

        /** Faster than the MIO goes, 72 km/h, in metres a second */
        private const val MAX_SPEED = 20.0

        /** What a rider may have gone beyond the speed, for a fix taken a little late */
        private const val REACH_MARGIN_METERS = 200.0

        /** Between two places as close, the one nearer where the rider was wins */
        private const val DRIFT_WEIGHT = 0.25

        /** Two stretches' nearest places this close along are one */
        private const val SAME_PLACE_METERS = 30.0

        /** How far a bus the rider is on can be from them */
        const val MATCH_RADIUS_METERS = 300.0
        const val MATCH_BEHIND_METERS = 300.0
        const val MATCH_AHEAD_METERS = 400.0

        private fun hypot(x: Double, y: Double) = sqrt(x * x + y * y)
    }
}

/**
 * The value at [x] on the line through the points ([xs], [ys]), [xs]
 * rising: straight between two points, [ys]' first up to [xs]' first,
 * its last from [xs]' last on
 */
internal fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
    if (x <= xs.first()) return ys.first()
    if (x >= xs.last()) return ys.last()
    var i = 0
    while (i < xs.size - 2 && xs[i + 1] < x) i++
    val span = xs[i + 1] - xs[i]
    val t = if (span == 0.0) 0.0 else (x - xs[i]) / span
    return ys[i] + t * (ys[i + 1] - ys[i])
}

/** A bus matched to a ride: followed by its number from then on */
data class MatchedBus(val busNumber: String, val along: Double)
