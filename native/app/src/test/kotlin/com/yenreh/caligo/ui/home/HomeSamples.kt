package com.yenreh.caligo.ui.home

import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.cards.CardsState
import com.yenreh.caligo.ui.stops.StopsState

/** A home as a rider has it: two cards, three stops and their buses */
object HomeSamples {
    private val now = System.currentTimeMillis()

    /** A bus [minutes] away, with a margin so the countdown holds while the test runs */
    private fun bus(line: String, destination: String, minutes: Int, stop: String = "") = BusArrival(
        line = line,
        destination = destination,
        arrivalTime = now + minutes * 60_000L + 20_000L,
        vehicleId = "$line-$minutes",
        stopName = stop,
    )

    val cards = listOf(
        CardEntity(
            internalId = "1",
            id = "06051868",
            prefix = "19",
            suffix = "981",
            name = "Mía",
            position = 0,
            balance = 15300.0,
            lastUpdate = 1790597100000,
        ),
        CardEntity(
            internalId = "2",
            id = "06051869",
            prefix = "19",
            suffix = "982",
            name = "Universidad",
            position = 1,
            balance = 2400.0,
            lastUpdate = 1790597100000,
        ),
    )

    val stops = listOf(
        FavoriteStop(
            id = "500800",
            stopId = "500800",
            name = "Plaza de Cayzedo A1",
            customName = "Trabajo",
            anchorLatitude = 3.4516,
            anchorLongitude = -76.532,
            position = 0,
            lines = listOf("E21", "T31"),
        ),
        FavoriteStop(
            id = "station-12",
            name = "Estación Universidades",
            anchorLatitude = 3.37,
            anchorLongitude = -76.53,
            position = 1,
        ),
        FavoriteStop(
            id = "501200",
            stopId = "501200",
            name = "Cl 5 con Kr 66",
            anchorLatitude = 3.42,
            anchorLongitude = -76.54,
            position = 2,
            missingCount = 3,
        ),
    )

    val arrivals = mapOf(
        "500800" to listOf(
            bus("E21", "Terminal Paso del Comercio", 2),
            bus("A47", "Valle Grande", 4),
            bus("T31", "Universidades", 9),
        ),
        "station-12" to listOf(
            bus("T31", "Chiminangos", 0, stop = "Universidades A"),
            bus("E37", "Menga", 6, stop = "Universidades B"),
        ),
        "501200" to emptyList(),
    )

    fun settings(content: HomeContent) = Settings(homeContent = content, isLoading = false)

    val cardsState = CardsState(cards = cards)

    val stopsState = StopsState(favorites = stops, arrivals = arrivals)
}
