package com.yenreh.caligo.trips

import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A trip's buses from the app's repository: the request the line screen
 * makes, through the same guard and the same cache of each bus's
 * direction. Nothing of the rider's position goes out with it.
 */
class RepositoryTripSource(private val repository: StopsRepository) : TripSource {
    override suspend fun buses(line: String): List<LineBus> = withContext(Dispatchers.IO) { repository.getLineBuses(line) }
}
