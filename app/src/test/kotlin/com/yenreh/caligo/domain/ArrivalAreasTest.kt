package com.yenreh.caligo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** test/arrival_areas_test.dart */
class ArrivalAreasTest {
    /** About 111 m of latitude per step */
    private fun north(steps: Double) = GeoPoint(3.45 + steps * 0.001, -76.53)

    @Test
    fun onePointAnswersForStopsWithinReachOfIt() {
        // 250 m apart: only the middle stop reaches the other two
        val centres = areasCovering(listOf(north(0.0), north(2.25), north(4.5)), ARRIVALS_REACH_METERS)

        assertEquals(listOf(north(2.25)), centres)
    }

    @Test
    fun theMiddleOfTwoStopsAnswersForBothWhenNeitherCan() {
        // 445 m apart: past each other's reach, within the middle's
        val centres = areasCovering(listOf(north(0.0), north(4.0)), ARRIVALS_REACH_METERS)

        assertEquals(1, centres.size)
        assertEquals(north(2.0).latitude, centres.single().latitude, 1e-12)
    }

    @Test
    fun stopsFarApartEachGetTheirOwn() {
        val centres = areasCovering(listOf(north(0.0), north(20.0), north(40.0)), ARRIVALS_REACH_METERS)

        assertEquals(3, centres.size)
    }

    @Test
    fun nothingToAskAboutAsksNothing() {
        assertTrue(areasCovering(emptyList(), ARRIVALS_REACH_METERS).isEmpty())
    }
}
