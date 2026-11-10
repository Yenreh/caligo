package com.yenreh.caligo.domain.repositories

import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.entities.TransitLine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

/**
 * Stops, arrivals and lines, and what the trip planner needs of them:
 * the Flutter app's repository.
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

    /** Every MIO line */
    suspend fun getLines(): List<TransitLine>

    /** A line's route, both directions */
    suspend fun getLineStops(line: String): List<LineStop>

    /** Buses running on a line now, each with the direction of its trip when known */
    suspend fun getLineBuses(line: String): List<LineBus>

    /** Hours each line runs, by line name */
    suspend fun getLineHours(): Map<String, LineHours>

    /**
     * Every line's stops, by line name, for planning trips. Lines that
     * fail to load are left out; [onProgress] hears of each one done.
     * Routes past their age still answer, and are fetched again in the
     * background for next time.
     */
    suspend fun getAllLineStops(onProgress: ((done: Int, total: Int) -> Unit)? = null): Map<String, List<LineStop>>

    /**
     * How often each line comes around the hour of [at], epoch
     * milliseconds, learned from the arrivals seen so far; lines never
     * seen are missing
     */
    suspend fun lineHeadways(at: Long): Map<String, Duration>

    /**
     * Learn from rides timed by following their bus: the live time over
     * what the route alone estimates, by line, as rideReadings gives it
     */
    suspend fun observeRides(readings: Map<String, Double>)

    /**
     * How each line's rides run against the estimate from its route;
     * lines never followed are missing
     */
    suspend fun rideFactors(): Map<String, Double>

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

    /**
     * The newest answer already fetched that covers a stop, if within
     * [maxAge]: asked around a point within reach of it when [placed], the
     * stop's own place, or else around that same point. Asks nothing.
     */
    fun recentAnswer(latitude: Double, longitude: Double, placed: Boolean, maxAge: Duration): WaitAnswer? = null

    /** Keep the arrivals just fetched, to show at once on the next launch */
    suspend fun keepArrivals(arrivals: Map<String, List<BusArrival>>)
}
