package com.yenreh.caligo.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** A point on the map */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Straight-line metres between two points, flat over a city's span */
fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val metresPerDegree = 111320.0
    val dy = (lat2 - lat1) * metresPerDegree
    val dx = (lon2 - lon1) * metresPerDegree * cos((lat1 + lat2) / 2 * PI / 180)
    return sqrt(dx * dx + dy * dy)
}

/**
 * A stop this close to where the arrivals service is asked around is
 * surely in its answer: the service's radius, 300 m, less a margin for
 * rounding on their side
 */
const val ARRIVALS_REACH_METERS = 280.0

/**
 * Points to ask the arrivals service around so that each of [points] is
 * within [reach] metres of one of them, as few as a greedy pick finds:
 * each next centre is the one answering for most of those left. Besides
 * the points themselves, the midpoint of two a single request can span
 * is tried, since it answers for both; on a tie a point wins, as the
 * centre closest to what is asked about.
 */
fun areasCovering(points: List<GeoPoint>, reach: Double): List<GeoPoint> {
    fun meters(a: GeoPoint, b: GeoPoint) =
        metersBetween(a.latitude, a.longitude, b.latitude, b.longitude)

    val candidates = buildList {
        addAll(points)
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                if (meters(points[i], points[j]) <= 2 * reach) {
                    add(
                        GeoPoint(
                            (points[i].latitude + points[j].latitude) / 2,
                            (points[i].longitude + points[j].longitude) / 2,
                        ),
                    )
                }
            }
        }
    }
    // Which points each candidate answers for, worked out once
    val answers = candidates.map { candidate ->
        points.indices.filter { meters(candidate, points[it]) <= reach }.toSet()
    }

    val left = points.indices.toMutableSet()
    val centres = mutableListOf<GeoPoint>()
    while (left.isNotEmpty()) {
        var best = -1
        var bestCount = 0
        for ((c, answered) in answers.withIndex()) {
            val count = answered.count { it in left }
            if (count > bestCount) {
                best = c
                bestCount = count
            }
        }
        centres.add(candidates[best])
        left.removeAll(answers[best])
    }
    return centres
}
