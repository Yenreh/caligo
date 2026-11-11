package com.yenreh.caligo.ui.trips

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyLeg
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.ui.map.MapCamera
import kotlin.math.abs

/** What a line on the trip map stands for, which says how it is drawn */
enum class TripLineRole {
    /** The ride on now, through its stops: accent */
    RIDE,

    /** The walk on now: dotted ink */
    WALK,

    /** A ride still to come: quieter */
    RIDE_AHEAD,

    /** The ride waited for, on the line the rider chose for it: accent, as the one on now */
    RIDE_CHOSEN,

    /** A walk still to come: quieter, dotted */
    WALK_AHEAD,
}

/** What a spot on the trip map stands for */
enum class TripMarkRole { RIDER, BUS, ALIGHT, BOARD, DESTINATION }

data class TripMapLine(val id: String, val points: List<GeoPoint>, val role: TripLineRole)

data class TripMapMark(val id: String, val point: GeoPoint, val role: TripMarkRole)

/**
 * The trip map's content at a moment, a pure function of the journey and
 * the time: the leg on now and those still to come, the rider, their bus
 * and the stops that matter. Legs done are left out.
 */
data class TripMapModel(
    val lines: List<TripMapLine>,
    val marks: List<TripMapMark>,
    /** Where the rider is, as far as the app can tell */
    val rider: GeoPoint?,
    /** What to frame on opening: the leg on now, and the rider */
    val frame: List<GeoPoint>,
) {
    companion object {
        fun of(journey: Journey, now: Long): TripMapModel {
            val lines = mutableListOf<TripMapLine>()
            val marks = mutableListOf<TripMapMark>()
            var frame = emptyList<GeoPoint>()
            for ((i, leg) in journey.legs.withIndex()) {
                if (i < journey.index) continue
                val current = i == journey.index
                when (leg) {
                    is JourneyLeg.Walk -> {
                        val points = listOf(GeoPoint(leg.walk.fromLatitude, leg.walk.fromLongitude), leg.to)
                        lines.add(TripMapLine("walk:$i", points, if (current) TripLineRole.WALK else TripLineRole.WALK_AHEAD))
                        if (!leg.toStop) marks.add(TripMapMark("destination", leg.to, TripMarkRole.DESTINATION))
                        if (current) frame = points
                    }
                    is JourneyLeg.Ride -> {
                        val riding = journey.ride?.takeIf { current }
                        // The ride waited for, on the line chosen for it: that line's own route
                        val chosen = journey.chosenLines[i]?.takeIf { riding == null && leg.ride.lines.contains(it) }
                        val stops = riding?.plan?.let { it.stops.subList(it.board, it.target + 1) }
                            ?: chosen?.let { leg.ride.choose(it).stops }
                            ?: leg.ride.stops
                        val points = stops.map { GeoPoint(it.latitude, it.longitude) }
                        val role = when {
                            riding != null -> TripLineRole.RIDE
                            chosen != null -> TripLineRole.RIDE_CHOSEN
                            else -> TripLineRole.RIDE_AHEAD
                        }
                        lines.add(TripMapLine("ride:$i", points, role))
                        if (riding != null) {
                            marks.add(TripMapMark("alight:$i", points.last(), TripMarkRole.ALIGHT))
                            riding.bus?.let { marks.add(TripMapMark("bus", riding.route.pointAt(it.along), TripMarkRole.BUS)) }
                            frame = points
                        } else {
                            marks.add(TripMapMark("board:$i", points.first(), TripMarkRole.BOARD))
                            if (current) frame = listOf(points.first())
                            // The line chosen for the ride waited for: its route in view too
                            if (chosen != null && i == journey.pendingRide) frame = frame + points
                        }
                    }
                }
            }
            val rider = riderAt(journey, now)
            rider?.let { marks.add(TripMapMark("rider", it, TripMarkRole.RIDER)) }
            return TripMapModel(lines, marks, rider, frame + listOfNotNull(rider))
        }

        /** On the bus, where the ride's progress puts them; on foot, their fix, or the estimate along the walk */
        private fun riderAt(journey: Journey, now: Long): GeoPoint? = when (val phase = journey.phase(now)) {
            is JourneyPhase.Riding -> phase.trip.route.pointAt(phase.trip.progress(now).along)
            is JourneyPhase.Waiting -> journey.fix?.point ?: phase.leg.ride.board.let { GeoPoint(it.latitude, it.longitude) }
            is JourneyPhase.Walking -> if (!phase.estimated) {
                journey.fix?.point
            } else {
                val walk = phase.leg.walk
                val done = if (walk.meters > 0) 1 - phase.meters / walk.meters else 1.0
                GeoPoint(
                    walk.fromLatitude + done * (walk.toLatitude - walk.fromLatitude),
                    walk.fromLongitude + done * (walk.toLongitude - walk.fromLongitude),
                )
            }
        }
    }
}

/**
 * How the trip map follows the rider: it keeps them in the middle while
 * [following], until the user moves the map themselves, which [placed],
 * the camera last set from here, tells apart from the map's own moves.
 */
data class MapFollow(val following: Boolean = true, val placed: MapCamera? = null) {
    /**
     * With the map at [camera] and the rider at [rider]: the follow state
     * from now, and where to move the map, if anywhere
     */
    fun next(camera: MapCamera, rider: GeoPoint?): Pair<MapFollow, GeoPoint?> {
        val placed = placed
        if (placed != null && moved(camera, placed)) return MapFollow(following = false) to null
        if (!following || rider == null) return this to null
        if (close(camera.center, rider)) return copy(placed = camera) to null
        return copy(placed = camera.copy(center = rider)) to rider
    }

    /** Follow the rider again, as the button on the map asks */
    fun recenter(): MapFollow = MapFollow(following = true)

    private fun moved(camera: MapCamera, placed: MapCamera) =
        !close(camera.center, placed.center) || abs(camera.zoom - placed.zoom) > ZOOM_SLACK

    private fun close(a: GeoPoint, b: GeoPoint) =
        abs(a.latitude - b.latitude) < DEGREE_SLACK && abs(a.longitude - b.longitude) < DEGREE_SLACK

    private companion object {
        /** About 10 cm: what the map's own arithmetic moves it by */
        const val DEGREE_SLACK = 1e-6
        const val ZOOM_SLACK = 0.01
    }
}
