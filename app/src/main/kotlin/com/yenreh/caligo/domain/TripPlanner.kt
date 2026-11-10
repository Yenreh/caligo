package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.entities.WalkLeg
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Dart's RegExp follows JavaScript: `\d` is ASCII, `$` is the end of the
// text alone and `.` stops at the four line terminators. Spelled out, so
// the JVM and Android's ICU engine read them the same.

/**
 * Platform codes end station stop names, a letter up to E and a number:
 * "Aguablanca B4"
 */
private val PLATFORM = Regex("^([^\\n\\r\\u2028\\u2029]+) [A-E][0-9]+\\z")

/**
 * A street stop split in berths ends in P and a number, "Cl 70 entre Av 4
 * y 3C P2", but so do the stops numbered along a road out of town, which
 * stand far apart: "Vía La Buitrera P10" to "P25" spans almost 2 km
 */
private val BERTH = Regex("^([^\\n\\r\\u2028\\u2029]+) P[0-9]+\\z")

/** Berths of one stop stand within this of each other */
private const val BERTH_METERS = 100.0

/**
 * The station a stop belongs to, by name; null for a street stop, which
 * is a place of its own
 */
fun stationOf(stopName: String): String? = PLATFORM.find(stopName)?.groups?.get(1)?.value

/**
 * Whether two stops are one place to wait at: the same stop, two
 * platforms of one station, or two berths of one street stop
 */
fun samePlace(a: LineStop, b: LineStop): Boolean {
    if (a.stopId == b.stopId) return true
    val station = stationOf(a.name)
    if (station != null) return station == stationOf(b.name)
    val berth = BERTH.find(a.name)?.groups?.get(1)?.value
    return berth != null &&
        berth == BERTH.find(b.name)?.groups?.get(1)?.value &&
        metersBetween(a.latitude, a.longitude, b.latitude, b.longitude) <= BERTH_METERS
}

/**
 * A line in one direction: the stops it calls at, by index in the
 * network, and the estimated seconds from its first stop to each
 */
internal class Pattern(
    val line: String,
    val towards: String,
    val stops: IntArray,
    val seconds: DoubleArray,
    val route: List<LineStop>,
)

/** A stop and how far it is, from a point or from another stop */
internal class Near(val stop: Int, val meters: Double)

/**
 * Every route of the MIO as one network: its stops, the way each line
 * runs through them, and the short walks between stops close together.
 */
class TransitNetwork private constructor(
    /** Each stop once, whatever lines serve it */
    val stops: List<LineStop>,
    /** Position of each stop in [stops], by id */
    private val index: Map<String, Int>,
    internal val patterns: List<Pattern>,
    /** Per stop, the patterns calling at it, and where along them, side by side */
    internal val servedPattern: List<IntArray>,
    internal val servedPosition: List<IntArray>,
    /** Per stop, the stops a short walk away */
    internal val walks: List<List<Near>>,
) {
    fun stopById(id: String): LineStop? = index[id]?.let { stops[it] }

    val isEmpty: Boolean get() = patterns.isEmpty()

    /** Stops within [radius] metres of a point, with their distance */
    internal fun near(latitude: Double, longitude: Double, radius: Double): List<Near> = buildList {
        for ((i, stop) in stops.withIndex()) {
            val meters = metersBetween(latitude, longitude, stop.latitude, stop.longitude)
            if (meters <= radius) add(Near(i, meters))
        }
    }

    /** The stop closest to a point, when one is within [radius] metres */
    fun nearestStop(latitude: Double, longitude: Double, radius: Double = 500.0): LineStop? {
        var best: LineStop? = null
        var bestMeters = radius
        for (stop in stops) {
            val meters = metersBetween(latitude, longitude, stop.latitude, stop.longitude)
            if (meters <= bestMeters) {
                best = stop
                bestMeters = meters
            }
        }
        return best
    }

    companion object {
        /**
         * Stops this close can be walked between to change buses; a
         * station's platforms fall well within it
         */
        const val TRANSFER_METERS = 350.0

        /**
         * Lines on their own lanes (T trunk, E express) run faster than
         * those in mixed traffic
         */
        private fun speed(line: String): Double = when (line.firstOrNull()) {
            'T', 'E' -> 7.0
            'P' -> 5.5
            else -> 4.5
        }

        /** Streets bend: a bus covers more than the straight line between stops */
        private const val ROUTE_DETOUR = 1.15

        /** Time spent at each stop along the way */
        private const val DWELL_SECONDS = 20.0

        /**
         * The estimated seconds from the first of [ordered], one direction
         * of [line] in route order, to each of its stops: what the planner
         * times its rides by, before the line's ride factor
         */
        fun routeSeconds(line: String, ordered: List<LineStop>): DoubleArray {
            val seconds = DoubleArray(ordered.size)
            for (i in 1 until ordered.size) {
                val previous = ordered[i - 1]
                val stop = ordered[i]
                val meters = metersBetween(previous.latitude, previous.longitude, stop.latitude, stop.longitude)
                seconds[i] = seconds[i - 1] + meters * ROUTE_DETOUR / speed(line) + DWELL_SECONDS
            }
            return seconds
        }

        /** Build from each line's stops, by line name */
        fun fromRoutes(routes: Map<String, List<LineStop>>): TransitNetwork {
            val index = HashMap<String, Int>()
            val stops = mutableListOf<LineStop>()
            val patterns = mutableListOf<Pattern>()

            for ((line, route) in routes) {
                val directions = route.mapTo(LinkedHashSet()) { it.direction }
                for (direction in directions) {
                    val ordered = route.filter { it.direction == direction }.sortedBy { it.sequence }
                    if (ordered.size < 2) continue

                    val positions = IntArray(ordered.size)
                    for ((i, stop) in ordered.withIndex()) {
                        positions[i] = index.getOrPut(stop.stopId) {
                            stops.add(stop)
                            stops.size - 1
                        }
                    }
                    val seconds = routeSeconds(line, ordered)
                    patterns.add(Pattern(line, ordered.last().name, positions, seconds, ordered))
                }
            }

            val servedPattern = List(stops.size) { mutableListOf<Int>() }
            val servedPosition = List(stops.size) { mutableListOf<Int>() }
            for ((p, pattern) in patterns.withIndex()) {
                for ((position, stop) in pattern.stops.withIndex()) {
                    servedPattern[stop].add(p)
                    servedPosition[stop].add(position)
                }
            }

            // Sorted by latitude, each stop only needs to look at a narrow band
            val walks = List(stops.size) { mutableListOf<Near>() }
            val byLatitude = stops.indices.sortedBy { stops[it].latitude }
            val band = TRANSFER_METERS / 111320.0
            for (i in byLatitude.indices) {
                val a = stops[byLatitude[i]]
                for (j in i + 1 until byLatitude.size) {
                    val b = stops[byLatitude[j]]
                    if (b.latitude - a.latitude > band) break
                    val meters = metersBetween(a.latitude, a.longitude, b.latitude, b.longitude)
                    if (meters > TRANSFER_METERS) continue
                    walks[byLatitude[i]].add(Near(byLatitude[j], meters))
                    walks[byLatitude[j]].add(Near(byLatitude[i], meters))
                }
            }

            return TransitNetwork(
                stops,
                index,
                patterns,
                servedPattern.map { it.toIntArray() },
                servedPosition.map { it.toIntArray() },
                walks,
            )
        }
    }
}

/** How a stop was reached in a round, by bus */
private class Ride(val pattern: Int, val boardPosition: Int, val alightPosition: Int)

/**
 * Finds ways between two places over a [TransitNetwork].
 *
 * Works in rounds, one more bus each (RAPTOR without timetables): the
 * MIO publishes none, so every ride and wait is an estimate and the
 * answer is a set of sensible options rather than a schedule.
 */
class TripPlanner(
    val network: TransitNetwork,
    /**
     * Time between buses of each line, as learned: half of it is the wait
     * for a line that has one, the usual figure for the rest
     */
    val headways: Map<String, Duration> = emptyMap(),
    /**
     * How each line's rides run against the estimate from its route, as
     * learned from buses followed stop to stop; 1 for a line not seen
     */
    val rideFactors: Map<String, Double> = emptyMap(),
) {
    /** Options from [from] to [to], best first. Lines in [leaveOut] are not used, such as those not running at the time. */
    fun plan(from: TripPlace, to: TripPlace, leaveOut: Set<String> = emptySet(), limit: Int = 5): List<TripOption> {
        val straight = metersBetween(from.latitude, from.longitude, to.latitude, to.longitude)
        val walk = walkOnly(from, to)
        if (straight <= WALK_ONLY_METERS) return listOf(walk)

        // One search only finds the fastest way with each number of buses.
        // Leaving out the lines of every trip found, one more at a time,
        // brings up the others, such as a slower feeder that reaches the
        // same transfer a little later.
        val found = mutableListOf<TripOption>()
        val tried = HashSet<String>()
        val queue = ArrayDeque<Set<String>>().apply { add(leaveOut) }
        var searches = 0
        while (queue.isNotEmpty() && searches < MAX_SEARCHES) {
            val out = queue.removeFirst()
            if (!tried.add(out.sorted().joinToString(","))) continue
            searches++
            for (option in search(from, to, out)) {
                found.add(option)
                for (ride in option.rides) queue.add(out + ride.line)
            }
        }
        if (straight <= WALK_OPTION_METERS) found.add(walk)

        val ranked = found.sortedBy(::cost)
        if (ranked.isEmpty()) return emptyList()

        // Options far worse than the best only fill the list
        val cutoff = cost(ranked.first()) * 1.6 + 600
        val kept = mutableListOf<TripOption>()
        val seen = HashSet<String>()
        for (option in ranked) {
            if (cost(option) > cutoff || !seen.add(option.signature)) continue
            val twin = kept.indexOfFirst { sameRide(it, option) }
            if (twin >= 0) {
                kept[twin] = merged(kept[twin], option)
            } else if (kept.none { hopsOnto(option, it) }) {
                kept.add(option)
            }
        }
        return kept.take(limit)
    }

    private fun walkOnly(from: TripPlace, to: TripPlace) =
        TripOption(listOf(WalkLeg.between(from.latitude, from.longitude, to.latitude, to.longitude)))

    /** The fastest trip with each number of buses, as long as it beats every trip with fewer */
    private fun search(from: TripPlace, to: TripPlace, leaveOut: Set<String>): List<TripOption> {
        var access = network.near(from.latitude, from.longitude, ACCESS_METERS)
        var egress = network.near(to.latitude, to.longitude, ACCESS_METERS)
        if (access.isEmpty()) access = network.near(from.latitude, from.longitude, WIDER_ACCESS_METERS)
        if (egress.isEmpty()) egress = network.near(to.latitude, to.longitude, WIDER_ACCESS_METERS)
        if (access.isEmpty() || egress.isEmpty()) return emptyList()

        val count = network.stops.size
        val best = DoubleArray(count) { Double.POSITIVE_INFINITY }

        // Per round: arrival by bus and on foot, and how each was reached
        val byRide = mutableListOf(DoubleArray(count) { Double.POSITIVE_INFINITY })
        val byWalk = mutableListOf(DoubleArray(count) { Double.POSITIVE_INFINITY })
        val rides = mutableListOf(arrayOfNulls<Ride>(count))
        val walksFrom = mutableListOf(IntArray(count) { -1 })

        var marked = LinkedHashSet<Int>()
        for (a in access) {
            val seconds = walkSeconds(a.meters)
            byWalk[0][a.stop] = seconds
            best[a.stop] = seconds
            marked.add(a.stop)
        }

        val options = mutableListOf<TripOption>()
        var bestArrival = Double.POSITIVE_INFINITY

        var round = 1
        while (round <= MAX_RIDES && marked.isNotEmpty()) {
            val previousRide = byRide[round - 1]
            val previousWalk = byWalk[round - 1]
            val ride = DoubleArray(count) { Double.POSITIVE_INFINITY }
            val walk = DoubleArray(count) { Double.POSITIVE_INFINITY }
            val rideFrom = arrayOfNulls<Ride>(count)
            val walkFrom = IntArray(count) { -1 }
            byRide.add(ride)
            byWalk.add(walk)
            rides.add(rideFrom)
            walksFrom.add(walkFrom)

            // Each pattern from the earliest stop along it reached last round
            val queue = LinkedHashMap<Int, Int>()
            for (stop in marked) {
                val servedPatterns = network.servedPattern[stop]
                val servedPositions = network.servedPosition[stop]
                for (s in servedPatterns.indices) {
                    val p = servedPatterns[s]
                    if (network.patterns[p].line in leaveOut) continue
                    val known = queue[p]
                    if (known == null || servedPositions[s] < known) queue[p] = servedPositions[s]
                }
            }

            val reached = LinkedHashSet<Int>()
            for ((p, start) in queue) {
                val pattern = network.patterns[p]
                val wait = typicalWait(pattern.line, headways).inWholeSeconds.toDouble()
                val pace = rideFactors[pattern.line] ?: 1.0
                var boardPosition = -1
                var boardedAt = 0.0

                for (position in start until pattern.stops.size) {
                    val stop = pattern.stops[position]

                    if (boardPosition >= 0) {
                        val arrival = boardedAt + (pattern.seconds[position] - pattern.seconds[boardPosition]) * pace
                        if (arrival < best[stop]) {
                            best[stop] = arrival
                            ride[stop] = arrival
                            rideFrom[stop] = Ride(p, boardPosition, position)
                            reached.add(stop)
                        }
                    }

                    // Boarding here instead, if that gets further sooner
                    val here = min(previousRide[stop], previousWalk[stop])
                    if (here.isFinite()) {
                        val board = here + wait
                        if (boardPosition < 0 ||
                            board - pattern.seconds[position] * pace <
                            boardedAt - pattern.seconds[boardPosition] * pace
                        ) {
                            boardPosition = position
                            boardedAt = board
                        }
                    }
                }
            }

            // A short walk to change buses, only right after a ride
            val walked = LinkedHashSet<Int>()
            for (stop in reached) {
                for (near in network.walks[stop]) {
                    val arrival = ride[stop] + walkSeconds(near.meters)
                    if (arrival < best[near.stop]) {
                        best[near.stop] = arrival
                        walk[near.stop] = arrival
                        walkFrom[near.stop] = stop
                        walked.add(near.stop)
                    }
                }
            }
            marked = LinkedHashSet<Int>(reached).apply { addAll(walked) }

            // Off the bus and on foot to the destination
            var arrival = Double.POSITIVE_INFINITY
            var last = -1
            for (e in egress) {
                val total = ride[e.stop] + walkSeconds(e.meters)
                if (total < arrival) {
                    arrival = total
                    last = e.stop
                }
            }
            if (last >= 0 && arrival < bestArrival) {
                bestArrival = arrival
                options.add(trace(from, to, round, last, byRide, byWalk, rides, walksFrom))
            }
            round++
        }

        return options
    }

    /**
     * Walk the labels back from the last stop to the origin, gathering
     * the rides; the walks between them follow from where they stop
     */
    private fun trace(
        from: TripPlace,
        to: TripPlace,
        lastRound: Int,
        last: Int,
        byRide: List<DoubleArray>,
        byWalk: List<DoubleArray>,
        rides: List<Array<Ride?>>,
        walksFrom: List<IntArray>,
    ): TripOption {
        val found = mutableListOf<RideLeg>()
        var round = lastRound
        var stop = last
        var onBus = true
        while (round > 0) {
            if (!onBus) {
                stop = walksFrom[round][stop]
                onBus = true
                continue
            }

            val ride = rides[round][stop]!!
            val pattern = network.patterns[ride.pattern]
            found.add(
                RideLeg(
                    line = pattern.line,
                    towards = pattern.towards,
                    stops = pattern.route.subList(ride.boardPosition, ride.alightPosition + 1).toList(),
                    wait = typicalWait(pattern.line, headways),
                    duration = (
                        (pattern.seconds[ride.alightPosition] - pattern.seconds[ride.boardPosition]) *
                            (rideFactors[pattern.line] ?: 1.0)
                        ).roundToLong().seconds,
                ),
            )
            stop = pattern.stops[ride.boardPosition]
            round--
            onBus = byRide[round][stop] <= byWalk[round][stop]
        }

        return TripOption.through(
            fromLatitude = from.latitude,
            fromLongitude = from.longitude,
            rides = found.reversed(),
            toLatitude = to.latitude,
            toLongitude = to.longitude,
        )
    }

    companion object {
        /** How far anyone walks to a first stop or from a last one; widened once when nothing is that close */
        const val ACCESS_METERS = 900.0
        const val WIDER_ACCESS_METERS = 1800.0

        /** What a change of bus costs beyond the time, when ranking options */
        val TRANSFER_PENALTY = 4.minutes

        /**
         * Share of the time on foot counted again when ranking options: most
         * would rather ride a little longer than walk as long
         */
        const val WALK_RELUCTANCE = 0.5

        /** Two changes at most */
        const val MAX_RIDES = 3

        /** Searches for one plan: each takes a few milliseconds */
        const val MAX_SEARCHES = 16

        /** Places closer than this are only walked */
        const val WALK_ONLY_METERS = 400.0

        /** Walking beats the bus up to this far, so it is offered alongside */
        const val WALK_OPTION_METERS = 1500.0

        /** A ride this short only saves a few steps */
        private const val HOP_STOPS = 2

        private val TWIN_SLACK = 3.minutes

        private fun walkSeconds(meters: Double) = meters * WalkLeg.DETOUR / WalkLeg.SPEED

        /**
         * How an option ranks, lower first: its time, a few minutes for each
         * change of bus, and half again the time on foot
         */
        fun cost(option: TripOption): Double =
            option.duration.inWholeSeconds +
                option.transfers * TRANSFER_PENALTY.inWholeSeconds +
                option.legs.filterIsInstance<WalkLeg>().fold(0L) { sum, walk -> sum + walk.duration.inWholeSeconds } *
                WALK_RELUCTANCE

        /**
         * Whether [option] takes a hop of a stop or two to board the main ride
         * of [other], which gets there with fewer buses: the same trip with a
         * bus more, not another way to go
         */
        private fun hopsOnto(option: TripOption, other: TripOption): Boolean {
            if (other.rides.isEmpty() || option.rides.size <= other.rides.size) return false
            val main = mainRide(option)
            val hops = option.rides.any { it !== main && it.stopCount <= HOP_STOPS }
            if (!hops) return false
            val theirs = mainRide(other)
            return main.lines.any { it in theirs.lines } && samePlace(main.alight, theirs.alight)
        }

        /** The longest ride, which the others lead to or from */
        private fun mainRide(option: TripOption): RideLeg =
            option.rides.reduce { a, b -> if (b.duration > a.duration) b else a }

        /**
         * Lines sharing a stretch of road board and leave at the same stop,
         * or at platforms of one station: one option with either line reads
         * better than two nearly equal rows
         */
        private fun sameRide(a: TripOption, b: TripOption): Boolean {
            val ridesA = a.rides
            val ridesB = b.rides
            if (ridesA.isEmpty() || ridesA.size != ridesB.size) return false
            if ((a.duration - b.duration).absoluteValue > TWIN_SLACK) return false
            for (i in ridesA.indices) {
                if (!samePlace(ridesA[i].board, ridesB[i].board) || !samePlace(ridesA[i].alight, ridesB[i].alight)) {
                    return false
                }
            }
            return true
        }

        private fun merged(kept: TripOption, twin: TripOption): TripOption {
            val others = twin.rides
            var i = 0
            return TripOption(kept.legs.map { leg -> if (leg is RideLeg) leg.withVariant(others[i++]) else leg })
        }
    }
}
