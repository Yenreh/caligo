package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.bus
import com.yenreh.caligo.trips.TripFixtures.pointAlong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry of following a ride: snapping, progress, the corridor, no jumping back, matching the bus */
class TripRouteTest {
    private val route = TripFixtures.line

    @Test
    fun theRouteIsMeasuredInMetresAlongItsStops() {
        assertEquals(0.0, route.at[0], 0.0)
        assertEquals(400.0, route.at[1], 1.0)
        assertEquals(3600.0, route.length, 3.0)
    }

    @Test
    fun aPointSnapsToWhereItIsAlongTheRouteAndHowFarOff() {
        val snap = route.snaps(pointAlong(1000.0, north = 30.0)).single()
        assertEquals(1000.0, snap.along, 1.0)
        assertEquals(30.0, snap.offset, 1.0)
    }

    @Test
    fun beyondTheCorridorThereIsNothingToSnapTo() {
        assertTrue(route.snaps(pointAlong(1000.0, north = TripRoute.CORRIDOR + 20)).isEmpty())
        assertEquals(TripRoute.CORRIDOR + 20, route.offset(pointAlong(1000.0, north = TripRoute.CORRIDOR + 20)), 1.0)
        assertEquals(Follow.Off, route.follow(900.0, pointAlong(1000.0, north = 400.0), 15_000))
    }

    @Test
    fun aFixALittleBehindHoldsTheRiderWhereTheyWere() {
        // GPS noise: 30 m behind, then ahead again
        assertEquals(Follow.On(1000.0), route.follow(1000.0, pointAlong(970.0), 15_000))
        assertEquals(1100.0, (route.follow(1000.0, pointAlong(1100.0), 15_000) as Follow.On).along, 0.5)
    }

    @Test
    fun aFixFarBehindMovesNothing() {
        assertEquals(Follow.Elsewhere, route.follow(1000.0, pointAlong(600.0), 15_000))
    }

    @Test
    fun aFixFurtherThanTheBusCouldGoMovesNothing() {
        // 2 km in 15 s
        assertEquals(Follow.Elsewhere, route.follow(1000.0, pointAlong(3000.0), 15_000))
        // The same 2 km in two minutes is a ride
        assertEquals(3000.0, (route.follow(1000.0, pointAlong(3000.0), 120_000) as Follow.On).along, 1.0)
    }

    /** Out 1 km east and back west along a street 60 m north: both legs pass close by */
    private val loop = TripRoute(
        listOf(
            LineStop("a", "A", 3.45, -76.54, 0, 1),
            LineStop("b", "B", 3.45, -76.54 + 0.009, 0, 2),
            LineStop("c", "C", 3.45 + 60 / 111320.0, -76.54 + 0.009, 0, 3),
            LineStop("d", "D", 3.45 + 60 / 111320.0, -76.54, 0, 4),
        ),
    )

    @Test
    fun aLegOfTheRouteRunningBackCloseByDoesNotDrawTheRiderAhead() {
        // On the way out, 200 m along, nearer the leg back than the leg out
        val point = GeoPoint(3.45 + 40 / 111320.0, -76.54 + 0.0018)
        assertEquals(2, loop.snaps(point).size)
        val follow = loop.follow(180.0, point, 15_000) as Follow.On
        assertEquals(200.0, follow.along, 2.0)
        // Coming back, 1.8 km along, the same point is on the leg back
        val back = loop.follow(1750.0, GeoPoint(3.45 + 20 / 111320.0, -76.54 + 0.0018), 15_000) as Follow.On
        assertTrue(back.along.toString(), back.along > 1700)
    }

    /** Two stops 30 m apart, as two berths of one stop */
    private val close = TripRoute(
        listOf(
            LineStop("a", "A", 3.45, -76.54, 0, 1),
            LineStop("b", "B", 3.45, -76.54 + 0.0036, 0, 2),
            LineStop("c", "C", 3.45, -76.54 + 0.0036 + 30 / 111150.0, 0, 3),
            LineStop("d", "D", 3.45, -76.54 + 0.0072, 0, 4),
        ),
    )

    @Test
    fun noiseAmongStopsCloseTogetherNeverTakesTheRiderBack() {
        var along = 380.0
        var left = close.stopsLeft(along, 3)
        // Fixes jumping between the two berths and a little behind
        for (fixAlong in listOf(410.0, 395.0, 432.0, 400.0, 415.0, 380.0, 450.0)) {
            val follow = close.follow(along, GeoPoint(3.45, -76.54 + fixAlong / 111150.0), 10_000)
            if (follow is Follow.On) {
                assertTrue("$fixAlong took the rider back", follow.along >= along)
                along = follow.along
            }
            val now = close.stopsLeft(along, 3)
            assertTrue("stops left went up at $fixAlong", now <= left)
            left = now
        }
        assertEquals(1, left)
    }

    @Test
    fun aStopCountsAsReachedAsTheBusPullsIn() {
        assertEquals(0, route.stopAt(399.0 - TripRoute.AT_STOP_METERS))
        assertEquals(1, route.stopAt(401.0 - TripRoute.AT_STOP_METERS))
        assertEquals(7, route.stopsLeft(0.0, 7))
        assertEquals(1, route.stopsLeft(2500.0, 7))
        assertEquals(0, route.stopsLeft(2790.0, 7))
        assertEquals(0, route.stopsLeft(3000.0, 7))
    }

    @Test
    fun theRidersBusIsTheNearestAlongTheRouteGoingTheSameWay() {
        val buses = listOf(
            // The other way, right there
            bus("back", 1000.0, direction = 1),
            // Far ahead
            bus("ahead", 2500.0),
            // Behind, its position lagging
            bus("mine", 920.0),
            // Off the route
            bus("off", 1000.0, north = 600.0),
        )
        assertEquals("mine", route.match(buses, 0, 1000.0)?.busNumber)
        assertEquals(920.0, route.match(buses, 0, 1000.0)!!.along, 1.0)
    }

    @Test
    fun aBusOfUnknownDirectionCanBeTheRiders() {
        assertEquals("unknown", route.match(listOf(bus("unknown", 1050.0, direction = null)), 0, 1000.0)?.busNumber)
    }

    @Test
    fun noBusNearMatchesNone() {
        assertNull(route.match(listOf(bus("ahead", 2500.0), bus("behind", 200.0)), 0, 1000.0))
        // Near along the route, but far from the rider's own fix
        assertNull(route.match(listOf(bus("near", 1100.0)), 0, 1000.0, riderPoint = pointAlong(1000.0, north = 400.0)))
    }

    @Test
    fun aPointAlongTheRouteIsOnIt() {
        val p = route.pointAt(1000.0)
        assertEquals(1000.0, route.snaps(p).single().along, 1.0)
        assertEquals(route.stops.last().longitude, route.pointAt(1e9).longitude, 0.0)
    }
}
