package com.yenreh.caligo.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the maps give CARTO up for OpenStreetMap, and take it back */
class CartoFallbackTest {
    private var clock = 1_790_000_000_000L
    private val fallback = CartoFallback { clock }

    private fun hear(vararg outcomes: TileOutcome) = outcomes.forEach(fallback::heard)

    @Test
    fun threeRefusalsInARowSwitchEveryMap() {
        val before = fallback.choice()
        hear(TileOutcome.REFUSED, TileOutcome.REFUSED)
        assertEquals(0, fallback.switches.value)
        hear(TileOutcome.REFUSED)
        assertEquals(1, fallback.switches.value)
        // A map made before moves to OpenStreetMap; one made now starts there
        assertFalse(before.cartoAt(fallback.switches.value))
        assertFalse(fallback.choice().carto)
    }

    @Test
    fun tilesStillOnTheirWayAsItGivesWaySwitchNothingMore() {
        hear(TileOutcome.REFUSED, TileOutcome.REFUSED, TileOutcome.REFUSED)
        hear(TileOutcome.REFUSED, TileOutcome.REFUSED, TileOutcome.REFUSED, TileOutcome.ANSWERED)
        assertEquals(1, fallback.switches.value)
        // The cooldown counts from the switch itself
        clock += CartoFallback.COOLDOWN.inWholeMilliseconds
        assertTrue(fallback.cartoAllowed())
    }

    @Test
    fun noConnectionAndMissingTilesSayNothingOfCarto() {
        hear(TileOutcome.REFUSED, TileOutcome.UNREACHABLE, TileOutcome.MISSING, TileOutcome.KEPT, TileOutcome.REFUSED)
        assertEquals(0, fallback.switches.value)
        hear(TileOutcome.REFUSED)
        assertEquals(1, fallback.switches.value)
    }

    @Test
    fun aTileAnsweredStartsTheCountOver() {
        hear(TileOutcome.REFUSED, TileOutcome.REFUSED, TileOutcome.ANSWERED, TileOutcome.REFUSED, TileOutcome.REFUSED)
        assertEquals(0, fallback.switches.value)
        assertTrue(fallback.cartoAllowed())
    }

    @Test
    fun afterTheCooldownOnlyMapsMadeFromThenOnTryCartoAgain() {
        hear(TileOutcome.REFUSED, TileOutcome.REFUSED, TileOutcome.REFUSED)
        val duringCooldown = fallback.choice()
        clock += CartoFallback.COOLDOWN.inWholeMilliseconds - 1
        assertFalse(fallback.cartoAllowed())
        clock += 1
        assertTrue(fallback.cartoAllowed())
        // The map already on OpenStreetMap keeps it; a new one tries CARTO
        assertFalse(duringCooldown.cartoAt(fallback.switches.value))
        val after = fallback.choice()
        assertTrue(after.cartoAt(fallback.switches.value))
        // Refused again: it gives way the same way
        hear(TileOutcome.REFUSED, TileOutcome.REFUSED, TileOutcome.REFUSED)
        assertFalse(after.cartoAt(fallback.switches.value))
        assertFalse(fallback.cartoAllowed())
    }
}
