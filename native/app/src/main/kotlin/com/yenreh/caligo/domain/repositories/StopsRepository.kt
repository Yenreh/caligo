package com.yenreh.caligo.domain.repositories

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

/**
 * Stops and arrivals: the part of the Flutter app's repository the home
 * and the stop screens need. Lines, routes and trips come with their
 * screens.
 */
interface StopsRepository {
    /** Stops saved by the user */
    suspend fun getFavorites(): List<FavoriteStop>

    /** Save a stop; throws when its id is already saved */
    suspend fun addFavorite(stop: FavoriteStop)

    suspend fun removeFavorite(stopId: String)

    /** Save changes to a stop the user already keeps */
    suspend fun updateFavorite(stop: FavoriteStop)

    /** Position to assign to the next saved stop */
    suspend fun nextPosition(): Int

    /** Stops around a position, with their upcoming buses */
    suspend fun getNearbyStops(latitude: Double, longitude: Double): List<NearbyStop>

    /**
     * Stops around a position, as soon as the service answers, and again
     * once those it can are placed on the map
     */
    fun locatedStops(latitude: Double, longitude: Double): Flow<List<NearbyStop>>

    /** MIO station catalog */
    suspend fun getStations(): List<Station>

    /** Lines serving a stop */
    suspend fun getLinesByStop(stopId: String): List<StopLine>

    /**
     * Buses on their way to the given stops, reusing recent answers and
     * grouping the rest into as few requests as possible
     */
    suspend fun arrivalsFor(stops: List<LineStop>, maxAge: Duration = 1.minutes): LiveArrivals

    /**
     * Where each of [stopIds] stands, for those whose place is known: from
     * the routes, or worked out from the arrivals service
     */
    suspend fun stopPositions(stopIds: Iterable<String>): Map<String, GeoPoint>

    /**
     * Arrivals of the favorites as last fetched, when that was within
     * [maxAge]; null otherwise
     */
    suspend fun recentArrivals(maxAge: Duration): Map<String, List<BusArrival>>?

    /** Keep the arrivals just fetched, to show at once on the next launch */
    suspend fun keepArrivals(arrivals: Map<String, List<BusArrival>>)
}
