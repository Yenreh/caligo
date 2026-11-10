package com.yenreh.caligo.ui.trips

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.trips.JourneyFixtures
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.busOn
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.fixAt
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.ui.map.MapCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trip map's content and how it follows the rider, as pure functions */
class TripMapModelTest {
    private val journey = Journey.planned(option, DESTINATION, NOW)

    @Test
    fun onTheWalkTheLegsToComeAreQuieter() {
        val model = TripMapModel.of(journey, NOW)
        assertEquals(
            listOf(TripLineRole.WALK, TripLineRole.RIDE_AHEAD, TripLineRole.WALK_AHEAD, TripLineRole.RIDE_AHEAD, TripLineRole.WALK_AHEAD),
            model.lines.map { it.role },
        )
        assertEquals(
            setOf(TripMarkRole.BOARD, TripMarkRole.DESTINATION, TripMarkRole.RIDER),
            model.marks.map { it.role }.toSet(),
        )
        // On the estimate, the rider at the start of the walk
        assertEquals(JourneyFixtures.origin.longitude, model.rider!!.longitude, 1e-6)
        // Framed on the walk, the rider in it
        assertEquals(3, model.frame.size)
    }

    @Test
    fun aFixPlacesTheRiderOnTheWalk() {
        val fix = GeoPoint(JourneyFixtures.origin.latitude, JourneyFixtures.origin.longitude + 0.001)
        val model = TripMapModel.of(journey.fixed(fixAt(fix, NOW + MINUTE)), NOW + MINUTE)
        assertEquals(fix, model.rider)
    }

    @Test
    fun onTheBusTheRideItsBusTheStopToGetOffAtAndTheRiderShow() {
        var riding = journey.took("T31", NOW + 6 * MINUTE)
        riding = riding.fixed(fixAt(calle5Route.pointAt(500.0), NOW + 7 * MINUTE))
        riding = riding.withBuses("T31", listOf(busOn(calle5Route, "41022", 520.0)), NOW + 7 * MINUTE + 1_000)
        val model = TripMapModel.of(riding, NOW + 7 * MINUTE + 1_000)
        // The walk done is gone
        assertEquals(
            listOf(TripLineRole.RIDE, TripLineRole.WALK_AHEAD, TripLineRole.RIDE_AHEAD, TripLineRole.WALK_AHEAD),
            model.lines.map { it.role },
        )
        assertEquals(7, model.lines.first().points.size)
        val bus = model.marks.single { it.role == TripMarkRole.BUS }
        assertEquals(calle5Route.pointAt(520.0).longitude, bus.point.longitude, 1e-6)
        val alight = model.marks.single { it.role == TripMarkRole.ALIGHT }
        assertEquals(TripFixtures.out[6].longitude, alight.point.longitude, 1e-9)
        assertEquals(calle5Route.pointAt(500.0).longitude, model.rider!!.longitude, 1e-6)
    }

    @Test
    fun aSingleRideShowsItsStopsToTheStopToGetOffAt() {
        val model = TripMapModel.of(Journey.single(TripFixtures.plan, NOW), NOW)
        assertEquals(listOf(TripLineRole.RIDE), model.lines.map { it.role })
        assertEquals(8, model.lines.single().points.size)
        assertTrue(model.marks.none { it.role == TripMarkRole.DESTINATION })
    }

    // Following the rider

    private val camera = MapCamera(GeoPoint(3.45, -76.54), 15.0)

    @Test
    fun itMovesTheMapToTheRiderWhileFollowing() {
        val rider = GeoPoint(3.451, -76.539)
        val (follow, to) = MapFollow().next(camera, rider)
        assertEquals(rider, to)
        assertTrue(follow.following)
        // The map got there: nothing more to do
        val (settled, again) = follow.next(camera.copy(center = rider), rider)
        assertNull(again)
        assertTrue(settled.following)
    }

    @Test
    fun aPanOfTheUsersOwnStopsFollowingUntilAskedAgain() {
        val rider = GeoPoint(3.451, -76.539)
        val (placed, _) = MapFollow().next(camera, rider)
        // The user drags the map away
        val (stopped, to) = placed.next(camera.copy(center = GeoPoint(3.46, -76.55)), rider)
        assertFalse(stopped.following)
        assertNull(to)
        // The rider moves on: the map stays where the user left it
        assertNull(stopped.next(camera.copy(center = GeoPoint(3.46, -76.55)), GeoPoint(3.452, -76.538)).second)
        // The button follows them again
        val (back, toRider) = stopped.recenter().next(camera.copy(center = GeoPoint(3.46, -76.55)), GeoPoint(3.452, -76.538))
        assertTrue(back.following)
        assertEquals(GeoPoint(3.452, -76.538), toRider)
    }

    @Test
    fun aZoomOfTheUsersOwnStopsFollowingToo() {
        val rider = GeoPoint(3.451, -76.539)
        val (placed, _) = MapFollow().next(camera, rider)
        assertFalse(placed.next(camera.copy(center = rider, zoom = 16.5), rider).first.following)
    }
}
