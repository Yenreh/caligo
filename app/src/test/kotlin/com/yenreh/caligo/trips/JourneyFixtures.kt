package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.TripFix
import com.yenreh.caligo.domain.TripRoute
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.out
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Duration.Companion.minutes

/**
 * A planner trip the journey tests follow: a walk of 300 m to Plaza de
 * Cayzedo, the E21 or the T31 six stops along Calle 5, a walk of 150 m
 * to the A47, four stops north on it, and a walk of 250 m to the end
 */
object JourneyFixtures {
    private val metersPerLongitude = 111320.0 * cos(out[0].latitude * PI / 180)

    fun east(point: GeoPoint, meters: Double) = GeoPoint(point.latitude, point.longitude + meters / metersPerLongitude)

    private fun north(point: GeoPoint, meters: Double) = GeoPoint(point.latitude + meters / 111320.0, point.longitude)

    private fun at(stop: LineStop) = GeoPoint(stop.latitude, stop.longitude)

    val origin = east(at(out[0]), -300.0)

    /** The A47 going north from a street beside the seventh stop of Calle 5 */
    val a47 = (0 until 6).map { i ->
        val p = north(at(out[6]), 150.0 + 400.0 * i)
        LineStop("52${i}0", "Kr 22 con Cl ${10 + i}", p.latitude, p.longitude, 0, i + 1)
    }

    val destination = east(at(a47[4]), 250.0)

    private val calle5 = out.subList(0, 7)
    val e21 = RideLeg("E21", "Universidades", calle5, wait = 3.minutes, duration = 6.minutes)
    val t31 = RideLeg("T31", "Universidades", calle5, wait = 5.minutes, duration = 6.minutes)
    val first = e21.withVariant(t31)
    val second = RideLeg("A47", "Valle Grande", a47.subList(0, 5), wait = 4.minutes, duration = 7.minutes)

    val option = TripOption.through(origin.latitude, origin.longitude, listOf(first, second), destination.latitude, destination.longitude)

    const val DESTINATION = "Unicentro"

    val calle5Route = TripRoute(calle5)
    val a47Route = TripRoute(a47.subList(0, 5))

    /** The point [meters] along the walk from [from] to [to] */
    fun between(from: GeoPoint, to: GeoPoint, meters: Double): GeoPoint {
        val total = com.yenreh.caligo.domain.metersBetween(from.latitude, from.longitude, to.latitude, to.longitude)
        val t = if (total == 0.0) 1.0 else (meters / total).coerceIn(0.0, 1.0)
        return GeoPoint(from.latitude + t * (to.latitude - from.latitude), from.longitude + t * (to.longitude - from.longitude))
    }

    fun fixAt(point: GeoPoint, at: Long, accuracy: Double = 10.0) = TripFix(point, accuracy, at)

    fun busOn(route: TripRoute, number: String, along: Double, direction: Int? = 0): LineBus {
        val p = route.pointAt(along)
        return LineBus(number, p.latitude, p.longitude, direction)
    }

    /** The arrivals at the first ride's stop: an E21 in [e21In] and a T31 in [t31In] minutes */
    fun firstArrivals(fetchedAt: Long, e21In: Int = 8, t31In: Int = 10) = LiveArrivals(
        fetchedAt,
        listOf(
            NearbyStop(
                out[0].stopId,
                out[0].name,
                20.0,
                listOf(
                    BusArrival("E21", "Universidades", fetchedAt + e21In * MINUTE, "637001", out[0].name),
                    BusArrival("T31", "Universidades", fetchedAt + t31In * MINUTE, "815001", out[0].name),
                ),
                out[0].latitude,
                out[0].longitude,
            ),
        ),
    )

    /** The arrivals at the A47's stop */
    fun secondArrivals(fetchedAt: Long) = LiveArrivals(
        fetchedAt,
        listOf(
            NearbyStop(
                a47[0].stopId,
                a47[0].name,
                20.0,
                listOf(BusArrival("A47", "Valle Grande", fetchedAt + 4 * MINUTE, "261001", a47[0].name)),
                a47[0].latitude,
                a47[0].longitude,
            ),
        ),
    )

    val start = NOW

    /** A bus of [line] at the first ride's stop, due [minutes] from [at] */
    fun bus(line: String, vehicle: String, at: Long, minutes: Double) =
        BusArrival(line, "Universidades", at + (minutes * MINUTE).toLong(), vehicle, out[0].name)

    /** What [journey]'s trip heard at [at] of the ride it waits for: [buses] at its stop */
    fun heard(journey: com.yenreh.caligo.domain.Journey, at: Long, vararg buses: BusArrival): RideBuses =
        RideBuses(checkNotNull(journey.pendingRide)).heard(
            LiveArrivals(at, listOf(NearbyStop(out[0].stopId, out[0].name, 20.0, buses.toList())), answeredAt = mapOf(out[0].stopId to at)),
            journey.pendingCandidates,
        )
}
