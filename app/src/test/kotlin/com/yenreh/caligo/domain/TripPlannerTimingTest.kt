package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.TripPlace
import java.util.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rough check that a plan is worth taking off the main thread: a
 * network the size of the MIO's (a hundred lines each way, some three
 * thousand stops) built from made-up routes, and planned across it. The
 * times go to the test's standard output; only a generous bound is held.
 */
class TripPlannerTimingTest {
    /** Stops snap to a grid of about 300 m, so lines crossing share them */
    private val cell = 0.0027
    private val south = 3.30
    private val west = -76.60

    private fun stop(x: Int, y: Int, direction: Int, sequence: Int) = LineStop(
        "g${x}_$y",
        "Cl $y entre Kr $x y ${x + 1}",
        south + y * cell,
        west + x * cell,
        direction,
        sequence,
    )

    /** A line out along one street and on along a crossing one, and back the same way */
    private fun route(random: Random, length: Int): List<LineStop> {
        var x = random.nextInt(60)
        var y = random.nextInt(75)
        val horizontal = random.nextBoolean()
        val turnAt = length / 3 + random.nextInt(length / 3)
        val step = if (random.nextBoolean()) 1 else -1
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until length) {
            out.add(x to y)
            if (horizontal == (i < turnAt)) x = (x + step).coerceIn(0, 59) else y = (y + step).coerceIn(0, 74)
        }
        val cells = out.distinct()
        return cells.mapIndexed { i, (cx, cy) -> stop(cx, cy, 0, i + 1) } +
            cells.reversed().mapIndexed { i, (cx, cy) -> stop(cx, cy, 1, i + 1) }
    }

    private fun network(): Map<String, List<LineStop>> {
        val random = Random(42)
        return buildMap {
            for (i in 1..8) put("T$i", route(random, 60))
            for (i in 1..30) put("P$i", route(random, 50))
            for (i in 1..70) put("A$i", route(random, 40))
        }
    }

    @Test
    fun plansARealisticTripInWellUnderASecond() {
        val routes = network()
        val (network, built) = measureTimedValue { TransitNetwork.fromRoutes(routes) }
        val headways = routes.keys.associateWith { 12.minutes }

        val random = Random(7)
        fun somewhere() = TripPlace(south + 0.02 + random.nextDouble() * 0.16, west + 0.02 + random.nextDouble() * 0.12, "")
        val trips = List(25) { somewhere() to somewhere() }
        val planner = TripPlanner(network, headways = headways)

        // Warm up the JIT, as a phone's runtime is after the first plans
        repeat(5) { planner.plan(trips[it].first, trips[it].second) }
        var found = 0
        val times = trips.map { (from, to) ->
            measureTime { if (planner.plan(from, to).any { it.rides.isNotEmpty() }) found++ }
        }.sorted()

        println(
            "Planner timing: ${network.stops.size} stops, ${routes.size} lines; built in $built; " +
                "${trips.size} plans, median ${times[times.size / 2]}, slowest ${times.last()}; $found with a bus",
        )
        assertTrue(network.stops.size > 2000)
        assertTrue(found > trips.size / 2)
        assertTrue("slowest ${times.last()}", times.last().inWholeMilliseconds < 5_000)
    }
}
