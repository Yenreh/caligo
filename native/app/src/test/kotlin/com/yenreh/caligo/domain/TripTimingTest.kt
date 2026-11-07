package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** test/trip_timing_test.dart */
class TripTimingTest {
    /** 2026-09-29 10:00, local time, as Dart's DateTime(2026, 9, 29, 10, 0) */
    private val start = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 10, 0)
    }.timeInMillis

    private fun minutesIn(minutes: Int) = start + minutes.minutes.inWholeMilliseconds

    private fun stop(id: String, name: String, east: Double) = LineStop(id, name, 3.45, -76.53 + east, 0, 1)

    /** From a point 120 m west of the first board to one next to the last alight */
    private fun trip(rides: List<RideLeg>) = TripOption.through(
        fromLatitude = 3.45,
        fromLongitude = rides.first().board.longitude - 0.0011,
        rides = rides,
        toLatitude = 3.45,
        toLongitude = rides.last().alight.longitude,
    )

    private fun ride(line: String, board: LineStop, alight: LineStop) =
        RideLeg(line, "End", listOf(board, alight), typicalWait(line), 15.minutes)

    private fun bus(line: String, vehicle: String, minutes: Int) = BusArrival(line, "End", minutesIn(minutes), vehicle)

    private fun live(byStop: Map<LineStop, List<BusArrival>>) = LiveArrivals(
        fetchedAt = start,
        stops = byStop.map { (stop, buses) -> NearbyStop(stop.stopId, stop.name, 0.0, buses) },
    )

    private val board = stop("b", "Cl 5 entre Kr 1 y 2", 0.0)
    private val alight = stop("a", "Cl 5 entre Kr 30 y 31", 0.05)

    @Test
    fun waitsForTheLiveBusAndRidesUntilThatBusGetsThere() {
        val timed = timeTrip(
            trip(listOf(ride("T1", board, alight))),
            start = start,
            live = live(mapOf(board to listOf(bus("T1", "v1", 8)), alight to listOf(bus("T1", "v1", 20)))),
        )
        val ride = timed.firstRide!!

        assertEquals(minutesIn(8), ride.departsAt)
        assertEquals(12.minutes, ride.duration)
        assertTrue(ride.liveWait)
        assertTrue(ride.liveRide)
        assertTrue(timed.isLive)
    }

    @Test
    fun takesTheNextBusWhenTheFirstLeavesBeforeOneGetsThere() {
        val timed = timeTrip(
            trip(listOf(ride("T1", board, alight))),
            start = start,
            // The walk to the stop takes about two minutes
            live = live(mapOf(board to listOf(bus("T1", "v1", 1), bus("T1", "v2", 9)))),
        )

        assertEquals(minutesIn(9), timed.firstRide!!.departsAt)
        // Not seen where it is left: the ride is the estimate
        assertFalse(timed.firstRide!!.liveRide)
        assertEquals(15.minutes, timed.firstRide!!.duration)
    }

    @Test
    fun fallsBackToTheUsualWaitWhenNoBusIsOnItsWay() {
        val timed = timeTrip(trip(listOf(ride("A9", board, alight))), start = start)

        assertEquals(7.minutes, timed.firstRide!!.wait)
        assertFalse(timed.firstRide!!.liveWait)
        assertFalse(timed.isLive)
    }

    @Test
    fun usesHalfTheLearnedTimeBetweenBusesAsTheUsualWait() {
        val timed = timeTrip(
            trip(listOf(ride("A9", board, alight))),
            start = start,
            headways = mapOf("A9" to 12.minutes),
        )

        assertEquals(6.minutes, timed.firstRide!!.wait)
    }

    // Lines sharing a ride

    private val shared = ride("T1", board, alight).withVariant(ride("T2", board, alight))
    private val sharedLive = live(mapOf(board to listOf(bus("T1", "v1", 12), bus("T2", "v2", 4))))

    @Test
    fun ofLinesSharingARideTakesTheOneThatComesFirst() {
        val timed = timeTrip(trip(listOf(shared)), start = start, live = sharedLive)

        assertEquals("T2", timed.firstRide!!.line)
        assertEquals(listOf("T1", "T2"), timed.firstRide!!.lines)
    }

    @Test
    fun ofLinesSharingARideKeepsTheOneChosenByHand() {
        val timed = timeTrip(trip(listOf(shared)), start = start, live = sharedLive, pinned = mapOf(0 to "T1"))

        assertEquals("T1", timed.firstRide!!.line)
        assertEquals(minutesIn(12), timed.firstRide!!.departsAt)
    }

    @Test
    fun countsABusAtAnotherPlatformOnlyIfItGetsWhereNeeded() {
        val platform = stop("b1", "Centro B1", 0.0)
        val across = stop("b2", "Centro B2", 0.0002)
        val end = stop("e", "Norte A1", 0.05)
        val timed = timeTrip(
            trip(listOf(ride("T1", platform, end))),
            start = start,
            live = live(
                mapOf(
                    // The platform across: one bus heads the other way, one does
                    // reach the end
                    across to listOf(bus("T1", "away", 3), bus("T1", "v9", 6)),
                    end to listOf(bus("T1", "v9", 25)),
                ),
            ),
        )

        assertEquals(minutesIn(6), timed.firstRide!!.departsAt)
        assertTrue(timed.firstRide!!.liveRide)
    }

    @Test
    fun anAnswerForOneOptionKeepsWhatTheOthersHad() {
        val other = stop("o", "Cl 9 entre Kr 5 y 4", 0.01)
        val older = live(mapOf(board to listOf(bus("T1", "v1", 8)), other to listOf(bus("A9", "v7", 5))))
        val newer = live(mapOf(board to listOf(bus("T1", "v1", 6))))

        val merged = newer.over(older)

        assertEquals(mapOf("b" to "v1", "o" to "v7"), merged.stops.associate { it.id to it.arrivals.single().vehicleId })
        assertEquals(minutesIn(6), merged.stops.first { it.id == "b" }.arrivals.single().arrivalTime)
    }

    @Test
    fun readsHowAFollowedRideRanAgainstItsEstimate() {
        val planned = trip(listOf(ride("T1", board, alight)))
        val timed = timeTrip(
            planned,
            start = start,
            live = live(mapOf(board to listOf(bus("T1", "v1", 8)), alight to listOf(bus("T1", "v1", 26)))),
        )

        // 18 minutes against the 15 of the estimate
        val readings = rideReadings(planned, timed)
        assertEquals(setOf("T1"), readings.keys)
        assertEquals(1.2, readings.getValue("T1"), 1e-9)
        // Timed by the usual figures, a ride says nothing
        assertTrue(rideReadings(planned, timeTrip(planned, start = start)).isEmpty())
        // Planned already a fifth slower, the route alone read 18 against 12.5
        val slower = rideReadings(planned, timed, rideFactors = mapOf("T1" to 1.2))
        assertEquals(setOf("T1"), slower.keys)
        assertEquals(1.44, slower.getValue("T1"), 1e-9)
    }

    // Beyond the Dart test: what is asked about

    @Test
    fun asksOnlyAboutRidesLeavingWithinTheHorizonEveryLineOfThem() {
        val far = stop("f", "Cl 70 entre Kr 1 y 2", 0.08)
        val farther = stop("g", "Cl 70 entre Kr 9 y 10", 0.12)
        // A ride of fifteen minutes, then one waited for an hour
        val trip = trip(listOf(shared, ride("A9", far, farther)))

        val asked = stopsWorthAsking(listOf(trip), start = start, headways = mapOf("A9" to 120.minutes))

        assertEquals(listOf("b", "a"), asked.map { it.stopId })
        assertEquals(
            listOf("b", "a", "f", "g"),
            stopsWorthAsking(listOf(trip), start = start, horizon = 600.minutes).map { it.stopId },
        )
    }
}
