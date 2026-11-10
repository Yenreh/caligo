package com.yenreh.caligo.waits

import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The waits' answers from the app's repository: the same requests the
 * screens make, through the same guard that leaves the service alone
 * when it pushes back, and the answers the screens fetched lately
 */
class RepositoryWaitSource(
    private val repository: StopsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : WaitSource {
    override suspend fun pointFor(bus: AwaitedBus): AskPoint {
        val anchor = AskPoint(bus.anchor.latitude, bus.anchor.longitude, placed = false)
        // An area is asked about as a whole, around where it was saved from
        if (bus.area) return anchor
        bus.place?.let { return AskPoint(it.latitude, it.longitude, placed = true) }
        val known = withContext(Dispatchers.IO) { repository.stopPositions(listOf(bus.stopId)) }[bus.stopId]
        return known?.let { AskPoint(it.latitude, it.longitude, placed = true) } ?: anchor
    }

    override fun recent(point: AskPoint, maxAge: Long): WaitAnswer? =
        repository.recentAnswer(point.latitude, point.longitude, point.placed, maxAge.milliseconds)

    override suspend fun ask(point: AskPoint): WaitAnswer {
        val stops = withContext(Dispatchers.IO) { repository.getNearbyStops(point.latitude, point.longitude) }
        return WaitAnswer(stops, now())
    }
}
