package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.entities.WalkLeg
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAT = 3.45
private const val LON = -76.53

/** About 440 m between stops */
private const val STEP = 0.004

/** A point given in steps east (x) and north (y) of the origin */
private typealias Point = Pair<Double, Double>

/** A line running one way through stops at the given offsets, in steps east (x) and north (y) of the origin */
private fun line(prefix: String, points: List<Point>) = points.mapIndexed { i, (x, y) ->
    LineStop("$prefix$i", "$prefix $i", LAT + y * STEP, LON + x * STEP, 0, i + 1)
}

private fun at(x: Double, y: Double) = TripPlace(LAT + y * STEP, LON + x * STEP, "($x, $y)")

private fun east(from: Int, to: Int, y: Double = 0.0) = (from..to).map { it.toDouble() to y }

private fun north(from: Int, to: Int, x: Double = 0.0) = (from..to).map { x to it.toDouble() }

private fun stop(id: String, name: String, x: Double, y: Double, sequence: Int) =
    LineStop(id, name, LAT + y * STEP, LON + x * STEP, 0, sequence)

/** test/trip_planner_test.dart */
class TripPlannerTest {
    @Test
    fun ridesOneLineWhenItGoesAllTheWay() {
        val planner = TripPlanner(TransitNetwork.fromRoutes(mapOf("T1" to line("a", east(0, 12)))))

        val best = planner.plan(at(0.0, 0.5), at(12.0, 0.5)).first()

        assertEquals(listOf("T1"), best.rides.map { it.line })
        assertEquals("a0", best.firstRide!!.board.stopId)
        assertEquals("a12", best.firstRide!!.alight.stopId)
        assertTrue(best.legs.first() is WalkLeg)
        assertTrue(best.legs.last() is WalkLeg)
        assertEquals(0, best.transfers)
    }

    @Test
    fun changesBusesWhereTwoLinesMeet() {
        val planner = TripPlanner(
            TransitNetwork.fromRoutes(
                mapOf(
                    "T1" to line("a", east(0, 10)),
                    // Crosses T1 at (10, 0) and heads north
                    "P2" to line("b", north(0, 10, x = 10.0)),
                ),
            ),
        )

        val best = planner.plan(at(0.0, 0.0), at(10.0, 10.0)).first()

        assertEquals(listOf("T1", "P2"), best.rides.map { it.line })
        assertEquals(1, best.transfers)
    }

    @Test
    fun walksBetweenNearbyStopsToChangeBuses() {
        val planner = TripPlanner(
            TransitNetwork.fromRoutes(
                mapOf(
                    "T1" to line("a", east(0, 10)),
                    // Starts 180 m north of T1's last stop
                    "P2" to line("b", north(0, 10, x = 10.0).map { (x, y) -> x to y + 0.4 }),
                ),
            ),
        )

        val best = planner.plan(at(0.0, 0.0), at(10.0, 10.4)).first()
        val walks = best.legs.filterIsInstance<WalkLeg>()

        assertEquals(listOf("T1", "P2"), best.rides.map { it.line })
        assertEquals(1, walks.count { it.toStop?.stopId == "b0" })
    }

    @Test
    fun offersLinesSharingTheSameStopsAsOneOption() {
        val route = east(0, 10)
        val network = TransitNetwork.fromRoutes(
            mapOf(
                "T1" to line("a", route),
                "T2" to line("a", route).map { it.copy(direction = 1) },
            ),
        )

        val options = TripPlanner(network).plan(at(0.0, 0.0), at(10.0, 0.0))

        assertEquals(setOf("T1", "T2"), options.first().firstRide!!.lines.toSet())
        assertEquals(2, options.first().firstRide!!.lines.size)
        assertEquals(1, options.count { it.rides.isNotEmpty() })
    }

    @Test
    fun showsASlowerFeederThatReachesTheSameTransferLater() {
        fun named(prefix: String, points: List<Point>, last: String): List<LineStop> {
            val stops = line(prefix, points)
            return stops.mapIndexed { i, s -> if (i == stops.size - 1) s.copy(name = last) else s }
        }

        val planner = TripPlanner(
            TransitNetwork.fromRoutes(
                mapOf(
                    // Two ways to the hub at (8, 0): a trunk line and a feeder just
                    // north of it, each to its own platform
                    "T1" to named("a", east(0, 8), "Hub B1"),
                    "A2" to named("c", east(0, 8, y = 0.3), "Hub B2"),
                    "T3" to line("d", north(0, 10, x = 8.2)),
                ),
            ),
        )

        val options = planner.plan(at(0.0, 0.15), at(8.2, 10.0), limit = 10)
        val firstLines = options.flatMap { it.firstRide?.lines.orEmpty() }

        assertTrue(firstLines.containsAll(listOf("T1", "A2")))
    }

    /** Two lines from platforms of one station to platforms of another, along parallel roads */
    private fun twoStations() = TransitNetwork.fromRoutes(
        mapOf(
            "T1" to listOf(stop("s1", "Centro B1", 0.0, 0.0, 1)) +
                (1 until 10).map { x -> stop("m$x", "Road $x", x.toDouble(), 0.0, x + 1) } +
                stop("e1", "Norte A1", 10.0, 0.0, 11),
            "T2" to listOf(stop("s2", "Centro B2", 0.0, 0.2, 1)) +
                (1 until 10).map { x -> stop("n$x", "Lane $x", x.toDouble(), 0.2, x + 1) } +
                stop("e2", "Norte A2", 10.0, 0.2, 11),
        ),
    )

    @Test
    fun mergesLinesLeavingFromPlatformsOfOneStation() {
        val options = TripPlanner(twoStations()).plan(at(0.0, 0.1), at(10.0, 0.1))
        val rides = options.filter { it.rides.isNotEmpty() }

        assertEquals(1, rides.size)
        assertEquals(setOf("T1", "T2"), rides.single().firstRide!!.lines.toSet())
    }

    @Test
    fun choosingAnotherLineOfARideFollowsItsOwnPlatforms() {
        val option = TripPlanner(twoStations()).plan(at(0.0, 0.1), at(10.0, 0.1)).first { it.rides.isNotEmpty() }
        val other = option.firstRide!!.lines.first { it != option.firstRide!!.line }

        val chosen = option.choose(0, other)
        val ride = chosen.firstRide!!
        val firstWalk = chosen.legs.first() as WalkLeg

        assertEquals(other, ride.line)
        assertEquals(if (other == "T2") "s2" else "s1", ride.board.stopId)
        assertEquals(ride.board.stopId, firstWalk.toStop!!.stopId)
        // The other lines stay on offer, in the same order
        assertEquals(option.firstRide!!.lines, ride.lines)
    }

    @Test
    fun keepsStreetStopsApartEvenWhenClose() {
        val a = LineStop("1", "Cl 121 entre Kr 26R y 26R1", 0.0, 0.0, 0, 1)
        val b = LineStop("2", "Kr 27 entre Cl 123 y 122", 0.0, 0.0, 0, 1)
        val platform = LineStop("3", "Aguablanca B4", 0.0, 0.0, 0, 1)

        assertFalse(samePlace(a, b))
        assertTrue(samePlace(a, a))
        assertEquals("Aguablanca", stationOf(platform.name))
        assertNull(stationOf(a.name))
    }

    @Test
    fun leavesOutTheLinesItIsToldTo() {
        val planner = TripPlanner(
            TransitNetwork.fromRoutes(
                mapOf(
                    "T1" to line("a", east(0, 12)),
                    "A9" to line("c", east(0, 12, y = 0.5)),
                ),
            ),
        )

        val options = planner.plan(at(0.0, 0.0), at(12.0, 0.0), leaveOut = setOf("T1"))

        assertTrue(options.flatMap { it.rides }.map { it.line }.all { it == "A9" })
    }

    @Test
    fun neverRidesAgainstALineDirection() {
        val planner = TripPlanner(TransitNetwork.fromRoutes(mapOf("T1" to line("a", east(0, 12)))))

        val options = planner.plan(at(12.0, 0.0), at(0.0, 0.0))

        assertTrue(options.flatMap { it.rides }.isEmpty())
    }

    @Test
    fun onlyWalksBetweenPlacesAFewStepsApart() {
        val planner = TripPlanner(TransitNetwork.fromRoutes(mapOf("T1" to line("a", east(0, 12)))))

        val options = planner.plan(at(0.0, 0.0), at(0.5, 0.0))

        assertEquals(1, options.size)
        assertTrue(options.single().rides.isEmpty())
    }

    @Test
    fun findsTheStopNearestAPoint() {
        val network = TransitNetwork.fromRoutes(mapOf("T1" to line("a", east(0, 5))))

        assertEquals("a3", network.nearestStop(LAT, LON + 3.1 * STEP)?.stopId)
        assertNull(network.nearestStop(LAT + 1, LON))
        assertEquals("a 2", network.stopById("a2")?.name)
    }

    @Test
    fun tellsBerthsOfOneStopFromStopsNumberedAlongARoad() {
        fun at(id: String, name: String, north: Double) = LineStop(id, name, LAT + north, LON, 0, 1)
        // Two berths 45 m apart; two road stops 330 m apart
        val berth = at("1", "Cl 70 entre Av 4 y 3C P1", 0.0)
        val otherBerth = at("2", "Cl 70 entre Av 4 y 3C P2", 0.0004)
        val road = at("3", "Vía La Buitrera P10", 0.0)
        val furtherOn = at("4", "Vía La Buitrera P11", 0.003)

        assertTrue(samePlace(berth, otherBerth))
        assertFalse(samePlace(road, furtherOn))
        assertNull(stationOf(road.name))
        assertEquals("Universidades", stationOf("Universidades D3"))
    }

    // What is learned of each line: two lines along parallel roads 220 m
    // apart, the origin on the first

    private val parallel = TransitNetwork.fromRoutes(
        mapOf(
            "A1" to line("a", east(0, 12)),
            "A2" to line("b", east(0, 12, y = 0.5)),
        ),
    )
    private val parallelFrom = at(0.0, 0.0)
    private val parallelTo = at(12.0, 0.25)

    @Test
    fun aLineSeenToComeSeldomLosesToOneAShortWalkAway() {
        assertEquals("A1", TripPlanner(parallel).plan(parallelFrom, parallelTo).first().firstRide!!.line)
        assertEquals(
            "A2",
            TripPlanner(parallel, headways = mapOf("A1" to 30.minutes))
                .plan(parallelFrom, parallelTo).first().firstRide!!.line,
        )
    }

    @Test
    fun aLineSeenToRunSlowLosesToOneAShortWalkAway() {
        val slow = TripPlanner(parallel, rideFactors = mapOf("A1" to 1.5))
        val best = slow.plan(parallelFrom, parallelTo).first()

        fun rideOnA1(planner: TripPlanner) = planner.plan(parallelFrom, parallelTo)
            .first { it.firstRide?.line == "A1" }.firstRide!!.duration

        assertEquals("A2", best.firstRide!!.line)
        assertEquals(
            rideOnA1(TripPlanner(parallel)).inWholeSeconds * 1.5,
            rideOnA1(slow).inWholeSeconds.toDouble(),
            1.0,
        )
    }

    // A hop onto a ride another option walks to: a trunk line east, and a
    // feeder hopping one stop from the origin, 330 m north of the trunk's
    // first stop, to its second

    private val hop = TransitNetwork.fromRoutes(
        mapOf(
            "T1" to line("a", east(0, 12)),
            "A5" to line("c", listOf(0.0 to 0.75, 1.0 to 0.0)),
        ),
    )
    private val hopFrom = at(0.0, 0.75)
    private val hopTo = at(12.0, 0.0)

    @Test
    fun aHopIsLeftOutWhenWalkingToTheRideRanksBetter() {
        // The hop saves under a minute and costs a change of bus
        val options = TripPlanner(hop, headways = mapOf("A5" to 690.seconds)).plan(hopFrom, hopTo)

        assertEquals(listOf("T1"), options.map { o -> o.rides.joinToString(">") { it.line } })
    }

    @Test
    fun aHopStaysWhenItRanksBetterThanWalking() {
        val options = TripPlanner(hop, headways = mapOf("A5" to 4.minutes)).plan(hopFrom, hopTo)

        assertEquals(listOf("A5>T1", "T1"), options.map { o -> o.rides.joinToString(">") { it.line } })
    }

    @Test
    fun ranksTimeOnFootAboveTimeOnABus() {
        val walk = TripOption(listOf(WalkLeg.between(LAT, LON, LAT, LON + 0.01)))

        assertEquals(
            walk.duration.inWholeSeconds * (1 + TripPlanner.WALK_RELUCTANCE),
            TripPlanner.cost(walk),
            0.0,
        )
    }
}
