package com.yenreh.caligo.data.repositories

import com.yenreh.caligo.data.datasources.ApiException
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    override fun locatedStops(latitude: Double, longitude: Double) = remote.locatedStops(latitude, longitude)

    override suspend fun getStations() = remote.getStations()

    override suspend fun getLinesByStop(stopId: String) = remote.getLinesByStop(stopId)

    override suspend fun getLines() = remote.getLines()

    override suspend fun getLineStops(line: String) = remote.getLineStops(line)

    override suspend fun getLineBuses(line: String) = remote.getLineBuses(line)

    override suspend fun getLineHours() = remote.getLineHours()

    override suspend fun getAllLineStops(onProgress: ((done: Int, total: Int) -> Unit)?): Map<String, List<LineStop>> {
        // A network a week old plans as well as a new one: it answers at once
        // and the new one comes in the background, for next time
        val lines = remote.getLines(staleOk = true)
        val total = lines.size
        val routes = LinkedHashMap<String, List<LineStop>>()
        var done = 0
        var failed = 0
        var lastError: ApiException? = null
        // Guards the four workers' shared lists and counts
        val lock = Mutex()

        val pending = lines.map { it.name }.toMutableList()
        coroutineScope {
            repeat(ROUTES_AT_ONCE) {
                launch {
                    while (true) {
                        val line = lock.withLock { pending.removeLastOrNull() } ?: break
                        val route = try {
                            remote.getLineStops(line, staleOk = true)
                        } catch (e: ApiException) {
                            lock.withLock {
                                failed++
                                lastError = e
                            }
                            null
                        }
                        lock.withLock {
                            if (route != null) routes[line] = route
                            onProgress?.invoke(++done, total)
                        }
                    }
                }
            }
        }

        // A few missing lines still leave a network worth planning on
        if (total > 0 && failed * 2 > total) throw lastError!!
        return routes
    }

    override suspend fun arrivalsFor(stops: List<LineStop>, maxAge: Duration): LiveArrivals =
        remote.arrivalsFor(stops, maxAge)

    override suspend fun lineHeadways(at: Long) = remote.lineHeadways(at)

    override suspend fun observeRides(readings: Map<String, Double>) = remote.observeRides(readings)

    override suspend fun rideFactors() = remote.rideFactors()

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

        /**
         * Requests at once while loading every route: few enough to be kind
         * to the service, enough to finish in seconds the first time
         */
        const val ROUTES_AT_ONCE = 4
    }
}
