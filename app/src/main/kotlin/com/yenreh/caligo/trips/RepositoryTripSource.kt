package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A trip's buses from the app's repository: the request the line screen
 * makes, through the same guard and the same cache of each bus's
 * direction; the arrivals of a ride's stops as the planner asks for them;
 * and what the rides teach, kept with the planner's ride factors. Nothing
 * of the rider's position goes out with it.
 */
class RepositoryTripSource(private val repository: StopsRepository) : TripSource {
    override suspend fun buses(line: String): List<LineBus> = withContext(Dispatchers.IO) { repository.getLineBuses(line) }

    override suspend fun arrivals(stops: List<LineStop>): LiveArrivals = withContext(Dispatchers.IO) { repository.arrivalsFor(stops) }

    override suspend fun observeRides(readings: Map<String, Double>) = withContext(Dispatchers.IO) { repository.observeRides(readings) }
}
