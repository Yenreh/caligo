package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arrivals that fade out, on a clock of the test's own */
class FadingArrivalsTest {
    /** The moment the first answer is fetched */
    private val start = 1_794_330_000_000L

    private fun at(seconds: Int) = start + seconds * 1000L

    private fun bus(vehicle: String, dueIn: Int, line: String = "E21") =
        BusArrival(line = line, destination = "Universidades", arrivalTime = at(dueIn), vehicleId = vehicle)

    /** What each row reads at [now], as vehicle and phase */
    private fun FadingArrivals.read(now: Long) = rows(now).map { it.bus.vehicleId to it.phase }

    private val live = ArrivalPhase.Live
    private val atStop = ArrivalPhase.AtStop

    @Test
    fun busesListedAreLiveByTime() {
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("2", 600), bus("1", 90)), at(0))

        assertEquals(listOf("1" to live, "2" to live), fading.read(at(0)))
    }

    @Test
    fun aNearBusThatLeavesIsAtTheStopThenPassedThenGone() {
        // Due 90 s after the answer listing it, gone from the next
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 90), bus("2", 900)), at(0))
            .fed("A", listOf(bus("2", 870)), at(30))

        assertEquals(listOf("1" to atStop, "2" to live), fading.read(at(30)))
        // At the stop up to a minute past its time
        assertEquals("1" to atStop, fading.read(at(90 + 59)).first())
        // Then passed, after the live rows, by whole minutes since its time
        assertEquals(listOf("2" to live, "1" to ArrivalPhase.Passed(1)), fading.read(at(90 + 60)))
        assertEquals("1" to ArrivalPhase.Passed(2), fading.read(at(90 + 179)).last())
        assertEquals("1" to ArrivalPhase.Passed(3), fading.read(at(90 + 239)).last())
        // Then it goes
        assertEquals(listOf("2" to live), fading.read(at(90 + 240)))
    }

    @Test
    fun aBusDueTwoMinutesOutWhenLastListedStillFades() {
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("1", 120)), at(0)).fed("A", emptyList(), at(30))

        assertEquals(listOf("1" to atStop), fading.read(at(30)))
    }

    @Test
    fun aBusThatLeavesFurtherAwayGoesAtOnce() {
        // Due 121 s after it was last listed: it may have changed route or lost its signal
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("1", 121)), at(0)).fed("A", emptyList(), at(30))

        assertEquals(emptyList<Pair<String, ArrivalPhase>>(), fading.read(at(30)))
        assertTrue(fading.isEmpty)
    }

    @Test
    fun aBusLeavingAfterItsTimeIsPassedAtOnce() {
        // Still listed at its time, gone two minutes later
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("1", 0)), at(0)).fed("A", emptyList(), at(120))

        assertEquals(listOf("1" to ArrivalPhase.Passed(2)), fading.read(at(120)))
    }

    @Test
    fun aBusWithoutAVehicleGoesAtOnce() {
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("", 60)), at(0)).fed("A", listOf(bus("", 50)), at(30))

        // Not told from the next one: never doubled as at the stop
        assertEquals(listOf("" to live), fading.read(at(30)))
    }

    @Test
    fun aBusListedAgainIsLiveWithItsNewTime() {
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 60)), at(0))
            .fed("A", emptyList(), at(30))
            .fed("A", listOf(bus("1", 150)), at(60))

        val rows = fading.rows(at(60))
        assertEquals(listOf("1" to live), rows.map { it.bus.vehicleId to it.phase })
        assertEquals(at(150), rows.single().bus.arrivalTime)
    }

    @Test
    fun withoutAnAnswerNothingLeaves() {
        // A failed request feeds nothing: the bus stays live, its countdown at zero
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("1", 60)), at(0))

        assertEquals(listOf("1" to live), fading.read(at(300)))
    }

    @Test
    fun anOlderAnswerChangesNothingAndTheSameOneAgainLeavesAllAsItWas() {
        val fed = FadingArrivals.EMPTY.fed("A", listOf(bus("1", 60)), at(30)).fed("A", emptyList(), at(60))

        // One fetched before the last fed comes in late
        assertEquals(fed.read(at(60)), fed.fed("A", listOf(bus("1", 60)), at(0)).read(at(60)))
        // A reused area is the same answer again
        assertEquals(fed.read(at(60)), fed.fed("A", emptyList(), at(60)).read(at(60)))
    }

    @Test
    fun aStopMissingFromAnAreaAnswerHasNoBusLeft() {
        val fading = FadingArrivals.EMPTY
            .fedWhole(mapOf("A" to listOf(bus("1", 60)), "B" to listOf(bus("2", 300))), at(0))
            // The service leaves out the stop the last bus reached
            .fedWhole(mapOf("B" to listOf(bus("2", 270))), at(30))

        val rows = fading.rows(at(30))
        assertEquals(listOf("A" to atStop, "B" to live), rows.map { it.stopId to it.phase })
    }

    @Test
    fun inAnAreaAVehicleIsToldByItsStop() {
        // One bus listed at two platforms: it reaches the first and goes on to the next
        val fading = FadingArrivals.EMPTY
            .fedWhole(mapOf("A" to listOf(bus("1", 60)), "B" to listOf(bus("1", 240))), at(0))
            .fedWhole(mapOf("B" to listOf(bus("1", 210))), at(30))

        val rows = fading.rows(at(30))
        assertEquals(listOf("A" to atStop, "B" to live), rows.map { it.stopId to it.phase })
    }

    @Test
    fun anAnswerForOneStopLeavesTheOthersAsTheyWere() {
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 60)), at(0))
            .fed("B", listOf(bus("2", 60)), at(0))
            .fed("A", emptyList(), at(30))

        assertEquals(listOf("A" to atStop, "B" to live), fading.rows(at(30)).map { it.stopId to it.phase })
        assertEquals(mapOf("A" to listOf(bus("1", 60))), fading.atTheStop(at(30)))
        assertEquals(emptyMap<String, List<BusArrival>>(), fading.atTheStop(at(60 + 60)))
    }

    @Test
    fun aFavoritesLinesApplyToEveryRow() {
        val favorite = FavoriteStop("A", "Centro A1", anchorLatitude = 0.0, anchorLongitude = 0.0, lines = listOf("T31"))
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 30, line = "T31"), bus("2", 40, line = "E21"), bus("3", 600, line = "T31")), at(0))
            .fed("A", listOf(bus("3", 570, line = "T31")), at(30))

        val rows = fading.rows(at(30)).filter { favorite.picks(it.bus.line) }
        assertEquals(listOf("1" to atStop, "3" to live), rows.map { it.bus.vehicleId to it.phase })
    }

    @Test
    fun atTheStopComesFirstThenLiveThenPassedAndTheLimitLeavesPassedOnTop() {
        // Three pass at different times, one reaches the stop, seven are on their way
        val first = listOf(bus("p1", -180), bus("p2", -120), bus("p3", -60), bus("s", 90)) +
            (1..7).map { bus("l$it", 300 + it * 60) }
        val fading = FadingArrivals.EMPTY.fed("A", first, at(0)).fed("A", first.drop(4), at(30))

        val now = at(30)
        val all = fading.read(now)
        assertEquals("s" to atStop, all.first())
        assertEquals((1..7).map { "l$it" to live }, all.subList(1, 8))
        // The most recent first
        assertEquals(listOf("p3", "p2", "p1"), all.drop(8).map { it.first })

        val shown = fading.rows(now).shown(5).map { it.bus.vehicleId }
        assertEquals(listOf("s", "l1", "l2", "l3", "l4", "p3", "p2"), shown)
    }

    @Test
    fun theMemoryEmptiesOnceEveryBusHasGone() {
        val fading = FadingArrivals.EMPTY.fed("A", listOf(bus("1", 60)), at(0)).fed("A", emptyList(), at(30))
        assertFalse(fading.isEmpty)

        // The next answer past its last minute forgets it
        assertTrue(fading.fed("A", emptyList(), at(60 + 240)).isEmpty)
        assertTrue(fading.fedWhole(emptyMap(), at(60 + 240)).isEmpty)
    }

    @Test
    fun liveRowsReadAsListed() {
        val buses = listOf(bus("1", 60), bus("2", 120))

        assertEquals(listOf("1" to live, "2" to live), buses.asLiveRows().map { it.bus.vehicleId to it.phase })
    }

    // A vehicle's next trip at the same stop, as the phone sampled it

    private fun FadingArrivals.of(vehicle: String, now: Long) =
        rows(now).filter { it.bus.vehicleId == vehicle }.map { it.phase to it.bus.arrivalTime }

    @Test
    fun aVehicleListedForItsNextTripWithoutLeavingReachedTheStop() {
        val t = PhoneSamples::time
        // +2.1 min at 14:46:09, then +46.8 at 14:46:39: a row for each trip,
        // the first due as the second first shows
        val next = t("14:46:39") + 2_808_000
        assertEquals(
            listOf(atStop to t("14:46:39"), live to next),
            PhoneSamples.fedUpTo("14:46:39").of("261001", t("14:46:39")),
        )
        assertEquals(
            listOf(ArrivalPhase.Passed(1) to t("14:46:39")),
            PhoneSamples.fedUpTo("14:48:09").of("261001", t("14:48:09")).filter { it.first != live },
        )
        // Gone four minutes after, the next trip live throughout
        assertEquals(listOf(ArrivalPhase.Passed(3)), PhoneSamples.fedUpTo("14:50:09").of("261001", t("14:50:09")).map { it.first }.filter { it != live })
        assertEquals(listOf(live), PhoneSamples.fedUpTo("14:51:09").of("261001", t("14:51:09")).map { it.first })
    }

    @Test
    fun aTripThatLeftNearItsTimeStaysFadedWhileItsNextTripIsListed() {
        // Due 90 s on, left out, then listed again for its next pass
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 90), bus("2", 900)), at(0))
            .fed("A", listOf(bus("2", 870)), at(30))
            .fed("A", listOf(bus("2", 840), bus("1", 60 * 46)), at(60))
            .fed("A", listOf(bus("2", 810), bus("1", 60 * 46 - 30)), at(140))
        assertEquals(
            listOf(atStop to at(90), live to at(60 * 46 - 30)),
            fading.of("1", at(140)),
        )
        // Passed until four minutes past its time, a row apart from its next trip's
        assertEquals(ArrivalPhase.Passed(3), fading.of("1", at(90 + 239)).first { it.first != live }.first)
        assertEquals(listOf(live), fading.of("1", at(90 + 240)).map { it.first })
    }

    /**
     * The pairs of answers sampled for the same vehicle at the same stop:
     * its time first, the minutes it was away, then its time [later] min
     * after, [seconds] s on
     */
    private fun pair(dueIn: Double, later: Double, seconds: Int): FadingArrivals = FadingArrivals.EMPTY
        .fed("A", listOf(bus("1", (dueIn * 60).toInt())), at(0))
        .fed("A", listOf(bus("1", (dueIn * 60 + later * 60).toInt())), at(seconds))

    @Test
    fun onlyAJumpNoDelayCanMakeIsANextTrip() {
        // The two next passes seen: 261001 at 514208, 939001 at 514202
        assertEquals(listOf(atStop, live), pair(2.1, 45.2, 30).of("1", at(30)).map { it.first })
        assertEquals(listOf(atStop, live), pair(0.1, 42.1, 30).of("1", at(30)).map { it.first })
        // The largest other changes, all the same bus later
        assertEquals(listOf(live), pair(22.1, 8.9, 120).of("1", at(120)).map { it.first })
        assertEquals(listOf(live), pair(6.8, 6.4, 180).of("1", at(180)).map { it.first })
        assertEquals(listOf(live), pair(6.6, 2.5, 60).of("1", at(60)).map { it.first })
        // A bus near the stop late by more than the clock ran, but less than five minutes more
        assertEquals(listOf(live), pair(3.0, 5.4, 30).of("1", at(30)).map { it.first })
    }

    @Test
    fun aDelayBuiltUpAnswerByAnswerStaysTheSameTrip() {
        // Ten minutes away, a minute later at each answer for a quarter of an hour
        var fading = FadingArrivals.EMPTY
        for (i in 0..30) fading = fading.fed("A", listOf(bus("1", 600 + i * 90)), at(i * 30))
        assertEquals(listOf(live to at(600 + 30 * 90)), fading.of("1", at(900)))
    }

    @Test
    fun theNextTripOfAFadedBusCountsFromItsLastListing() {
        // Due in 90 s, left, then listed 3 minutes on for 40 minutes later
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 90)), at(0))
            .fed("A", emptyList(), at(30))
            .fed("A", listOf(bus("1", 90 + 40 * 60)), at(180))
        assertEquals(listOf(live, ArrivalPhase.Passed(1)), fading.of("1", at(180)).map { it.first })
        // Seven minutes later than its time, five minutes on: the same bus, back
        val back = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 90)), at(0))
            .fed("A", emptyList(), at(30))
            .fed("A", listOf(bus("1", 90 + 7 * 60)), at(300))
        assertEquals(listOf(live), back.of("1", at(300)).map { it.first })
    }

    @Test
    fun aFarTripWhoseNextOneShowsReachedTheStopAsWell() {
        // Five minutes away, then its next pass: it got there meanwhile
        val fading = FadingArrivals.EMPTY
            .fed("A", listOf(bus("1", 300)), at(0))
            .fed("A", listOf(bus("1", 300 + 46 * 60)), at(30))
        assertEquals(listOf(atStop to at(30), live to at(300 + 46 * 60)), fading.of("1", at(30)))
    }
}
