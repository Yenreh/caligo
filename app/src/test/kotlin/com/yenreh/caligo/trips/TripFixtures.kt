package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripFix
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.TripRoute
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.ui.home.HomeSamples
import kotlin.math.PI
import kotlin.math.cos

/**
 * A route the trip tests ride, at [NOW]: the E21 out along Calle 5, ten
 * stops 400 m apart, and back, its stops a street to the north
 */
object TripFixtures {
    const val NOW = HomeSamples.NOW
    const val MINUTE = 60_000L
    const val LINE = "E21"

    private const val LATITUDE = 3.4500
    private const val LONGITUDE = -76.5400

    /** Degrees of longitude in 400 m at this latitude */
    private val STEP = 400.0 / (111320.0 * cos(LATITUDE * PI / 180))

    val names = listOf(
        "Plaza de Cayzedo A1",
        "Centro",
        "Calle 5 con Kr 10",
        "Calle 5 con Kr 15",
        "Calle 5 con Kr 22",
        "Calle 5 con Kr 34",
        "Calle 5 con Kr 39",
        "Pampalinda",
        "Calle 5 con Kr 66",
        "Universidades",
    )

    val out = names.mapIndexed { i, name -> LineStop("50${i}0", name, LATITUDE, LONGITUDE + STEP * i, 0, i + 1) }

    /** Back the other way, a block north: about 110 m */
    val back = names.reversed().mapIndexed { i, name ->
        LineStop("51${9 - i}0", name.replace(" A1", " A2"), LATITUDE + 0.001, LONGITUDE + STEP * (9 - i), 1, i + 1)
    }

    val route = out + back

    val took = TookRide(LINE, "Universidades", out[0].stopId, out[0].name, "637001", NOW + MINUTE)

    /** Boarded at the first stop, getting off at the eighth, Pampalinda */
    val plan: TripPlan = checkNotNull(TripPlan.of(took, route)).copy(alight = 7)

    val line = TripRoute(out)

    /** The point [along] metres along the route out, [north] metres to its north */
    fun pointAlong(along: Double, north: Double = 0.0): GeoPoint {
        val p = line.pointAt(along)
        return GeoPoint(p.latitude + north / 111320.0, p.longitude)
    }

    fun fix(along: Double, at: Long, north: Double = 0.0, accuracy: Double = 15.0) = TripFix(pointAlong(along, north), accuracy, at)

    fun bus(number: String, along: Double, direction: Int? = 0, north: Double = 0.0): LineBus {
        val p = pointAlong(along, north)
        return LineBus(number, p.latitude, p.longitude, direction)
    }
}
