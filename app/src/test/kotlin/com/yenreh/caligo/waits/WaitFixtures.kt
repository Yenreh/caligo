package com.yenreh.caligo.waits

import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.home.HomeSamples

/** Buses, stops and answers the wait tests are built on, at [NOW] */
object WaitFixtures {
    const val NOW = HomeSamples.NOW
    const val MINUTE = 60_000L

    const val STOP = "500800"
    const val STOP_NAME = "Plaza de Cayzedo A1"
    const val OTHER_STOP = "500300"

    val anchor = GeoPoint(3.4516, -76.5320)
    val place = GeoPoint(3.4517, -76.5321)

    fun bus(line: String, vehicle: String, at: Long, destination: String = "Universidades") =
        BusArrival(line = line, destination = destination, arrivalTime = at, vehicleId = vehicle, stopName = STOP_NAME)

    val t31 = bus("T31", "t31", NOW + 10 * MINUTE)
    val e21 = bus("E21", "e21", NOW + 4 * MINUTE, destination = "Terminal Paso del Comercio")

    fun awaited(bus: BusArrival, stopId: String = STOP, placed: Boolean = true) = AwaitedBus(
        stopId = stopId,
        stopName = STOP_NAME,
        bus = bus,
        anchor = anchor,
        place = if (placed) place else null,
    )

    /** An answer listing [buses] at [stopId], fetched at [at] */
    fun answer(at: Long, vararg buses: BusArrival, stopId: String = STOP) = WaitAnswer(
        stops = if (buses.isEmpty()) emptyList() else listOf(NearbyStop(stopId, STOP_NAME, 40.0, buses.toList())),
        fetchedAt = at,
    )
}
