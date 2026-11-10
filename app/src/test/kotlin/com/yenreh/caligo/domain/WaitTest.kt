package com.yenreh.caligo.domain

import com.yenreh.caligo.waits.WaitFixtures.MINUTE
import com.yenreh.caligo.waits.WaitFixtures.NOW
import com.yenreh.caligo.waits.WaitFixtures.answer
import com.yenreh.caligo.waits.WaitFixtures.awaited
import com.yenreh.caligo.waits.WaitFixtures.bus
import com.yenreh.caligo.waits.WaitFixtures.e21
import com.yenreh.caligo.waits.WaitFixtures.t31
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A wait read at moments, fed scripted answers: no clock runs */
class WaitTest {
    private val start = Wait.start(awaited(t31), NOW)

    private fun Wait.goes(now: Long): Wait = (step(now) as WaitStep.Goes).wait

    private fun Wait.ends(now: Long): WaitEnding = (step(now) as WaitStep.Ends).ending

    @Test
    fun countsDownFromTheRowUntilTheFirstAnswer() {
        assertEquals(WaitStatus.Coming(t31.arrivalTime), start.status(NOW))
        assertEquals(WaitStatus.Coming(t31.arrivalTime), start.status(NOW + MINUTE))
    }

    @Test
    fun followsTheTimeEachAnswerGives() {
        val moved = t31.copy(arrivalTime = t31.arrivalTime + MINUTE)
        val wait = start.fed(answer(NOW + 30_000, moved))
        assertEquals(WaitStatus.Coming(moved.arrivalTime), wait.status(NOW + 30_000))
        assertEquals(moved, wait.last)
    }

    @Test
    fun anAnswerOlderThanTheLastChangesNothing() {
        val moved = t31.copy(arrivalTime = t31.arrivalTime + MINUTE)
        val wait = start.fed(answer(NOW + 60_000, t31)).fed(answer(NOW + 30_000, moved))
        assertEquals(t31, wait.last)
    }

    @Test
    fun aBusLeavingNearItsTimeIsAtTheStopThenAsksThenEnds() {
        val near = Wait.start(awaited(e21), NOW)
        // Listed a minute away, then left out
        val due = e21.arrivalTime
        val wait = near.fed(answer(due - MINUTE, e21)).fed(answer(due - 30_000))
        assertEquals(WaitStatus.AtStop, wait.status(due - 30_000))
        assertEquals(WaitStatus.AtStop, wait.status(due + 59_000))
        // A minute past its time: "Did you take it?" for a minute
        assertEquals(WaitStatus.Asking, wait.status(due + MINUTE))
        assertFalse(wait.asks(due + MINUTE))
        assertEquals(WaitStatus.Asking, wait.goes(due + 2 * MINUTE - 1).status(due + 2 * MINUTE - 1))
        assertEquals(WaitEnding.PASSED, wait.ends(due + 2 * MINUTE))
    }

    @Test
    fun aFarBusThatDropsOutAndComesBackIsFollowedThroughout() {
        // Pushed out of the stop's five, ten minutes away
        val out = start.fed(answer(NOW + 30_000, e21))
        assertEquals(WaitStatus.Coming(t31.arrivalTime, seen = false), out.status(NOW + 30_000))
        assertEquals(NOW + 30_000, out.missingSince)
        // Still followed four minutes later, on its last known time
        val still = out.fed(answer(NOW + 4 * MINUTE, e21)).goes(NOW + 4 * MINUTE)
        assertEquals(WaitStatus.Coming(t31.arrivalTime, seen = false), still.status(NOW + 4 * MINUTE))
        // Back in the list: seen again, on the time it gives now
        val back = t31.copy(arrivalTime = t31.arrivalTime + 30_000)
        val seen = still.fed(answer(NOW + 4 * MINUTE + 30_000, back))
        assertEquals(WaitStatus.Coming(back.arrivalTime), seen.status(NOW + 5 * MINUTE))
        assertEquals(null, seen.missingSince)
        assertTrue(seen.step(NOW + 9 * MINUTE) is WaitStep.Goes)
    }

    @Test
    fun aFarBusMissingForMinutesIsNoLongerSeen() {
        val out = start.fed(answer(NOW + 30_000, e21))
        assertEquals(null, out.goes(NOW + 30_000 + Wait.MISSING_FOR.inWholeMilliseconds - 1).telling)
        val telling = out.goes(NOW + 30_000 + Wait.MISSING_FOR.inWholeMilliseconds)
        assertEquals(WaitStatus.NotSeen, telling.status(NOW + 6 * MINUTE))
        assertFalse(telling.asks(NOW + 6 * MINUTE))
        val end = NOW + 30_000 + Wait.MISSING_FOR.inWholeMilliseconds + Wait.TELL_FOR.inWholeMilliseconds
        assertTrue(telling.step(end - 1) is WaitStep.Goes)
        assertEquals(WaitEnding.NOT_SEEN, telling.ends(end))
    }

    @Test
    fun aMissingBusWellPastItsTimeIsNoLongerSeen() {
        val soon = Wait.start(awaited(bus("T31", "t31", NOW + 3 * MINUTE)), NOW)
        // Left out three minutes away: further than "near", so not at the stop
        val out = soon.fed(answer(NOW + 10_000))
        val past = NOW + 3 * MINUTE + Wait.WELL_PAST.inWholeMilliseconds
        assertEquals(null, out.goes(past - 1).telling)
        assertEquals(WaitEnding.NOT_SEEN, out.goes(past).telling)
    }

    @Test
    fun failuresSayNoDataThenEnd() {
        val failing = start.fed(answer(NOW + 10_000, t31)).failed(NOW + MINUTE)
        assertEquals(WaitStatus.NoData, failing.status(NOW + MINUTE))
        // Each later failure keeps when they began
        val longer = failing.failed(NOW + 2 * MINUTE)
        assertEquals(NOW + MINUTE, longer.failingSince)
        val telling = longer.goes(NOW + MINUTE + Wait.NO_DATA_FOR.inWholeMilliseconds)
        assertEquals(WaitStatus.NoDataEnded, telling.status(NOW + 7 * MINUTE))
        assertEquals(
            WaitEnding.NO_DATA,
            telling.ends(NOW + MINUTE + Wait.NO_DATA_FOR.inWholeMilliseconds + Wait.TELL_FOR.inWholeMilliseconds),
        )
    }

    @Test
    fun anAnswerAfterFailuresClearsThem() {
        val back = start.failed(NOW + MINUTE).fed(answer(NOW + 2 * MINUTE, t31))
        assertEquals(WaitStatus.Coming(t31.arrivalTime), back.status(NOW + 2 * MINUTE))
        assertEquals(null, back.failingSince)
    }

    @Test
    fun endsAtTheTimeLimit() {
        val limit = NOW + Wait.TIME_LIMIT.inWholeMilliseconds
        assertTrue(start.step(limit - 1) is WaitStep.Goes)
        assertEquals(WaitEnding.TIME_LIMIT, start.ends(limit))
    }

    @Test
    fun theAlertIsDueOnceWithinTwoMinutes() {
        assertFalse(start.alertDue(t31.arrivalTime - 2 * MINUTE - 1))
        assertTrue(start.alertDue(t31.arrivalTime - 2 * MINUTE))
        assertFalse(start.copy(alerted = true).alertDue(t31.arrivalTime - MINUTE))
        // Not for a bus not seen just now
        val out = start.fed(answer(NOW + 30_000, e21))
        assertFalse(out.alertDue(t31.arrivalTime - MINUTE))
    }

    @Test
    fun seededByTheRowsListAtTheStop() {
        val due = e21.arrivalTime
        val seen = FadingArrivals.EMPTY.fed("500800", listOf(e21), due - MINUTE).fed("500800", emptyList(), due - 20_000)
        val wait = Wait.start(awaited(e21), due - 10_000, seen)
        assertEquals(WaitStatus.AtStop, wait.status(due - 10_000))
        assertEquals(due - 20_000, wait.answeredAt)
    }
}
