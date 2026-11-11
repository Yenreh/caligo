package com.yenreh.caligo.data.map

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When CARTO's tiles give way to OpenStreetMap's, for the whole run and
 * every map at once. CARTO refusing [REFUSALS] tiles in a row, with none
 * answered between them, switches: its key refused or out of quota, a
 * rate limit, or a server failing past the loader's retries. A tile
 * missing or no connection says nothing of CARTO, as OpenStreetMap would
 * fare the same. After [COOLDOWN] a map made from then on tries CARTO
 * again; one showing OpenStreetMap keeps it until it is made again. Held
 * in memory alone: a new process starts on CARTO.
 */
class CartoFallback(private val now: () -> Long = System::currentTimeMillis) {
    private var refusals = 0
    private var switchedAt: Long? = null
    private val count = MutableStateFlow(0)

    /** How many times CARTO gave way this run: a map made before the last one moves to OpenStreetMap */
    val switches: StateFlow<Int> = count.asStateFlow()

    /** How asking CARTO for a tile went; nothing while it has given way, as for tiles asked before it did */
    @Synchronized
    fun heard(outcome: TileOutcome) {
        if (!cartoAllowed()) return
        when (outcome) {
            TileOutcome.ANSWERED -> refusals = 0
            TileOutcome.REFUSED -> if (++refusals >= REFUSALS) {
                refusals = 0
                switchedAt = now()
                count.value += 1
            }
            TileOutcome.KEPT, TileOutcome.MISSING, TileOutcome.UNREACHABLE -> {}
        }
    }

    /** Whether a map made now draws CARTO's tiles: never switched, or the cooldown over */
    @Synchronized
    fun cartoAllowed(): Boolean = switchedAt?.let { now() - it >= COOLDOWN.inWholeMilliseconds } ?: true

    /**
     * The choice of one map, made as it is: CARTO while allowed then, until
     * CARTO gives way again while it shows
     */
    fun choice(): MapTilesChoice = MapTilesChoice(cartoAllowed(), switches.value)

    companion object {
        /** CARTO's refusals in a row that switch every map */
        const val REFUSALS = 3

        /** How long OpenStreetMap stays before a new map tries CARTO again */
        val COOLDOWN = 10.minutes
    }
}

/** Whether one map draws CARTO's tiles: [carto] as it was made, at [switches] gone by */
data class MapTilesChoice(val carto: Boolean, val switches: Int) {
    /** With CARTO having given way [now] times: OpenStreetMap from the first after the map was made */
    fun cartoAt(now: Int): Boolean = carto && now == switches
}
