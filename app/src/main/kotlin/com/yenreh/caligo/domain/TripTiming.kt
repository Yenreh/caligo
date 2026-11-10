package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.entities.WalkLeg
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * How long a wait for [line] usually is when no bus is known: half the
 * time between its buses when that has been seen, otherwise a figure
 * by kind of line (trunk lines run most often, feeders least)
 */
fun typicalWait(line: String, headways: Map<String, Duration> = emptyMap()): Duration {
    // Dart's Duration ~/ 2, in whole microseconds
    headways[line]?.let { return (it.inWholeMicroseconds / 2).microseconds }
    return when (line.firstOrNull()) {
        'T', 'E' -> 3.minutes
        'P' -> 5.minutes
        else -> 7.minutes
    }
}

/**
 * [option] timed from [start], epoch milliseconds: each wait against the
 * first live bus that can still be caught, one already at the stop with
 * no wait when it is still there once the stop is reached, never one
 * passed; each ride by following that
 * bus to where it is left, and the usual figures wherever no bus is
 * known. Of the lines making a ride, the one arriving first is taken,
 * unless [pinned] names one for that ride, by its position among the
 * rides.
 */
fun timeTrip(
    option: TripOption,
    start: Long,
    live: LiveArrivals? = null,
    headways: Map<String, Duration> = emptyMap(),
    pinned: Map<Int, String> = emptyMap(),
): TripOption {
    val rides = option.rides
    if (rides.isEmpty()) return TripOption(option.legs, startsAt = start)

    val origin = option.legs.first() as WalkLeg
    val destination = option.legs.last() as WalkLeg
    var at = start
    var fromLatitude = origin.fromLatitude
    var fromLongitude = origin.fromLongitude
    val timed = mutableListOf<RideLeg>()

    for ((i, ride) in rides.withIndex()) {
        val pin = pinned[i]
        val lines = if (pin != null && pin in ride.lines) listOf(pin) else ride.lines

        var best: RideLeg? = null
        var bestArrival: Long? = null
        for (line in lines) {
            val variant = if (ride.variants.isEmpty()) ride else ride.choose(line)
            val walk = WalkLeg.between(fromLatitude, fromLongitude, variant.board.latitude, variant.board.longitude)
            val atStop = at + walk.duration.inWholeMilliseconds

            val bus = live?.nextBus(line, variant.board, variant.alight, atStop)
            val departs: Long
            val arrives: Long
            val liveRide: Boolean
            if (bus != null) {
                departs = bus.departsAt
                val reached = live.reachedAt(bus.bus.vehicleId, variant.alight, departs)
                liveRide = reached != null
                arrives = reached ?: (departs + variant.duration.inWholeMilliseconds)
            } else {
                departs = atStop + typicalWait(line, headways).inWholeMilliseconds
                liveRide = false
                arrives = departs + variant.duration.inWholeMilliseconds
            }

            if (bestArrival == null || arrives < bestArrival) {
                bestArrival = arrives
                best = variant.timed(
                    wait = (departs - atStop).milliseconds,
                    duration = (arrives - departs).milliseconds,
                    departsAt = departs,
                    liveWait = bus != null,
                    liveRide = liveRide,
                    busAtStop = bus?.atStop == true,
                )
            }
        }

        timed.add(best!!)
        at = bestArrival!!
        fromLatitude = best.alight.latitude
        fromLongitude = best.alight.longitude
    }

    return TripOption.through(
        fromLatitude = origin.fromLatitude,
        fromLongitude = origin.fromLongitude,
        rides = timed,
        toLatitude = destination.toLatitude,
        toLongitude = destination.toLongitude,
        startsAt = start,
    )
}

/**
 * Rides shorter than this say little of how a line runs: a minute either
 * way would swing them
 */
private val READABLE_RIDE = 5.minutes

/**
 * How long each ride of [timed] took by following its bus, against what
 * its route alone estimates: the live time over the estimate it had in
 * [planned], the same option before timing, with the [rideFactors] it
 * was planned with taken back out. By line.
 */
fun rideReadings(
    planned: TripOption,
    timed: TripOption,
    rideFactors: Map<String, Double> = emptyMap(),
): Map<String, Double> {
    val estimates = planned.rides
    val readings = LinkedHashMap<String, Double>()
    for ((i, ride) in timed.rides.withIndex()) {
        if (!ride.liveRide || i >= estimates.size) continue
        val estimate = if (estimates[i].variants.isEmpty()) estimates[i] else estimates[i].choose(ride.line)
        if (estimate.duration < READABLE_RIDE) continue
        readings[ride.line] =
            ride.duration.inWholeSeconds * (rideFactors[ride.line] ?: 1.0) / estimate.duration.inWholeSeconds
    }
    return readings
}

/**
 * The stops a set of options calls at while buses could already be on
 * their way to them: the service lists buses up to about this far ahead
 */
fun stopsWorthAsking(
    options: Iterable<TripOption>,
    start: Long,
    horizon: Duration = 50.minutes,
    headways: Map<String, Duration> = emptyMap(),
): List<LineStop> {
    val byId = LinkedHashMap<String, LineStop>()
    val until = start + horizon.inWholeMilliseconds
    for (option in options) {
        val rough = timeTrip(option, start = start, headways = headways)
        for ((i, ride) in option.rides.withIndex()) {
            val departs = rough.rides[i].departsAt
            if (departs != null && departs > until) continue
            for (variant in ride.variants.ifEmpty { listOf(ride) }) {
                byId[variant.board.stopId] = variant.board
                byId[variant.alight.stopId] = variant.alight
            }
        }
    }
    return byId.values.toList()
}
