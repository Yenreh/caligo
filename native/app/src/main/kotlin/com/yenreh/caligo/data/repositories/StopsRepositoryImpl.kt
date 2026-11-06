package com.yenreh.caligo.data.repositories

import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.datasources.StopsRemoteDatasource
import com.yenreh.caligo.data.json.busFromJson
import com.yenreh.caligo.data.json.toJson
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlin.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class StopsRepositoryImpl(
    private val local: StopsLocalDatasource,
    private val remote: StopsRemoteDatasource,
    private val cache: JsonCache,
) : StopsRepository {
    override suspend fun getFavorites() = local.getFavorites()

    override suspend fun addFavorite(stop: FavoriteStop) = local.insert(stop)

    override suspend fun removeFavorite(stopId: String) = local.delete(stopId)

    override suspend fun updateFavorite(stop: FavoriteStop) = local.update(stop)

    override suspend fun nextPosition() = local.nextPosition()

    override suspend fun getNearbyStops(latitude: Double, longitude: Double) =
        remote.getNearbyStops(latitude, longitude)

    override suspend fun arrivalsFor(stops: List<LineStop>, maxAge: Duration): LiveArrivals =
        remote.arrivalsFor(stops, maxAge)

    override suspend fun stopPositions(stopIds: Iterable<String>) = remote.stopPositions(stopIds)

    override suspend fun recentArrivals(maxAge: Duration): Map<String, List<BusArrival>>? {
        val kept = cache.read(ARRIVALS_KEY, maxAge) as? JsonObject ?: return null
        return try {
            buildMap {
                for ((id, buses) in kept) {
                    if (buses is JsonArray) {
                        put(id, buses.filterIsInstance<JsonObject>().map(::busFromJson))
                    }
                }
            }
        } catch (_: Exception) {
            // A kept snapshot is only a head start: without it, wait as before
            null
        }
    }

    override suspend fun keepArrivals(arrivals: Map<String, List<BusArrival>>) {
        cache.write(
            ARRIVALS_KEY,
            JsonObject(arrivals.mapValues { (_, buses) -> JsonArray(buses.map { it.toJson() }) }),
        )
    }

    private companion object {
        const val ARRIVALS_KEY = "favorite_arrivals"
    }
}
