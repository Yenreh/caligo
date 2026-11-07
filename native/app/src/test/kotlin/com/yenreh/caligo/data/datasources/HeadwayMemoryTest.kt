package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import java.util.Calendar
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The headway cases of test/stops_remote_datasource_test.dart */
class HeadwayMemoryTest {
    /** A local time, as Dart's DateTime(...) */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    @Test
    fun learnsHowOftenEachLineComesFromTheArrivalsSeen() = runTest {
        val memory = HeadwayMemory(JsonCache.None)
        val now = at(2026, 9, 29, 8, 15)
        fun bus(minutes: Int) = BusArrival(
            line = "T50",
            destination = "Andres Sanin",
            arrivalTime = now + minutes.minutes.inWholeMilliseconds,
            vehicleId = "v$minutes",
        )

        memory.observe(
            listOf(NearbyStop("s", "Aguablanca B4", 0.0, arrivals = listOf(bus(1), bus(10), bus(19), bus(28)))),
            now,
        )

        assertEquals(9.minutes, memory.at(now)["T50"])
        // An hour later still counts; a quiet night hour does not
        assertEquals(9.minutes, memory.at(now + 1.hours.inWholeMilliseconds)["T50"])
        assertNull(memory.at(now + 12.hours.inWholeMilliseconds)["T50"])
    }

    @Test
    fun aLineSeenComingIsKnownForAWhile() = runTest {
        val memory = HeadwayMemory(JsonCache.None)
        val now = at(2026, 9, 29, 8)

        memory.observe(
            listOf(NearbyStop("s", "Stop", 0.0, arrivals = listOf(BusArrival("A52", "Terminal", now, "1")))),
            now,
        )

        assertEquals(setOf("A52"), memory.seenSince(now - 14.days.inWholeMilliseconds))
        assertTrue(memory.seenSince(now + 1.days.inWholeMilliseconds).isEmpty())
    }

    @Test
    fun bunchedBusesAndClosingGapsTeachNothingAndANewReadingMovesItAThird() = runTest {
        val memory = HeadwayMemory(JsonCache.None)
        val now = at(2026, 9, 29, 8)
        fun stop(vararg minutes: Int) = NearbyStop(
            "s",
            "Stop",
            0.0,
            arrivals = minutes.map { BusArrival("T31", "", now + it.minutes.inWholeMilliseconds, "$it") },
        )

        // A minute apart, then over an hour apart: neither is how often it runs
        memory.observe(listOf(stop(0, 1), stop(0, 61)), now)
        assertNull(memory.at(now)["T31"])

        memory.observe(listOf(stop(0, 10)), now)
        memory.observe(listOf(stop(0, 20)), now)
        // 10 * 0.7 + 20 * 0.3
        assertEquals(13.minutes, memory.at(now)["T31"])
    }
}
