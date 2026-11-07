package com.yenreh.caligo.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.data.location.LocationAccuracy
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.data.location.LocationPermissionException
import com.yenreh.caligo.data.location.LocationServiceDisabledException
import com.yenreh.caligo.data.location.LocationSource
import com.yenreh.caligo.data.location.LocationTimeoutException
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.CardBalance
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.entities.TransitLine
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Cards kept in memory. The balance service fails for [failing] ids, as
 * the Dart test's fake; [failure] can make it throw anything else, and
 * [answer] stands in for the request itself. Saves land in [cards] and
 * are recorded; [saveFailure] makes them throw, and [saving] holds them.
 */
class FakeCardRepository(
    cards: List<CardEntity>,
    failing: Set<String> = emptySet(),
) : CardRepository {
    val cards = cards.toMutableList()

    /** Numbers whose balance the service refuses */
    val failing = failing.toMutableSet()
    val asked = mutableListOf<String>()
    var failure: Throwable? = null
    var answer: (suspend () -> Unit)? = null

    /** Thrown by every read, while set */
    var loadFailure: Throwable? = null

    /** In the datasource's order: by position, then by name */
    override suspend fun getAllCards(): List<CardEntity> {
        loadFailure?.let { throw it }
        return cards.sortedWith(compareBy<CardEntity> { it.position }.thenBy { it.name })
    }

    override suspend fun refreshCardBalance(card: CardEntity): CardBalance {
        asked.add(card.id)
        answer?.invoke()
        failure?.let { throw it }
        if (card.id in failing) throw ServerApiException("Proxy error", isRetryable = false)
        return CardBalance(balance = 9000.0, cardNumber = card.id, balanceDate = 1790608530123)
    }

    override suspend fun updateCardBalance(id: String, balance: Double, lastUpdate: Long) {
        val i = cards.indexOfFirst { it.id == id }
        cards[i] = cards[i].copy(balance = balance, lastUpdate = lastUpdate)
    }

    override suspend fun getCardById(id: String) = cards.firstOrNull { it.id == id }

    val updated = mutableListOf<CardEntity>()
    val deleted = mutableListOf<String>()
    var saveFailure: Throwable? = null
    var saving: (suspend () -> Unit)? = null

    override suspend fun createCard(card: CardEntity) {
        saving?.invoke()
        saveFailure?.let { throw it }
        cards.add(card)
    }

    /** By internal id, as the datasource */
    override suspend fun updateCard(card: CardEntity) {
        saving?.invoke()
        saveFailure?.let { throw it }
        updated.add(card)
        val i = cards.indexOfFirst { it.internalId == card.internalId }
        if (i >= 0) cards[i] = card
    }

    override suspend fun deleteCard(id: String) {
        saving?.invoke()
        saveFailure?.let { throw it }
        deleted.add(id)
        cards.removeAll { it.id == id }
    }
}

/**
 * Repository that records how the arrivals were asked for. Updates are
 * recorded in [saved] and leave the favorites as they were, as the Dart
 * test's fake; a favorite added or removed is, and changes the list.
 */
class FakeStopsRepository(
    favorites: List<FavoriteStop>,
    private val nearby: List<NearbyStop>,
    /** Where the stops stand, for those whose place is known */
    private val positions: Map<String, GeoPoint> = emptyMap(),
    var stations: List<Station> = emptyList(),
    /** Lines by stop id */
    var lines: Map<String, List<StopLine>> = emptyMap(),
) : StopsRepository {
    private val favorites = favorites.toMutableList()
    val added = mutableListOf<FavoriteStop>()
    val removed = mutableListOf<String>()
    var stationCalls = 0

    /** Thrown by the catalog, when set */
    var catalogFailure: Throwable? = null
    var nearbyCalls = 0
    val askedAround = mutableListOf<GeoPoint>()
    val saved = mutableListOf<FavoriteStop>()
    var kept: Map<String, List<BusArrival>>? = null

    /** Thrown by the arrivals service, when set */
    var failure: Throwable? = null

    /** Thrown when a favorite is saved, when set */
    var saveFailure: Throwable? = null

    /** Stands in for the request itself, when set */
    var answer: (suspend () -> Unit)? = null

    override suspend fun getFavorites() = favorites.toList()

    override suspend fun getNearbyStops(latitude: Double, longitude: Double): List<NearbyStop> {
        nearbyCalls++
        askedAround.add(GeoPoint(latitude, longitude))
        answer?.invoke()
        failure?.let { throw it }
        return nearby
    }

    /**
     * The stops around, as [getNearbyStops] answers them, then again with
     * the places [located] gives them, when it gives any
     */
    var located: Map<String, GeoPoint> = emptyMap()

    override fun locatedStops(latitude: Double, longitude: Double): Flow<List<NearbyStop>> = flow {
        val stops = getNearbyStops(latitude, longitude)
        emit(stops)
        if (located.isEmpty()) return@flow
        emit(
            stops.map { stop ->
                located[stop.id]?.let { stop.copy(latitude = it.latitude, longitude = it.longitude) } ?: stop
            },
        )
    }

    override suspend fun stopPositions(stopIds: Iterable<String>): Map<String, GeoPoint> =
        stopIds.mapNotNull { id -> positions[id]?.let { id to it } }.toMap()

    override suspend fun addFavorite(stop: FavoriteStop) {
        saveFailure?.let { throw it }
        // As the database refuses a second row with the same id
        check(favorites.none { it.id == stop.id }) { "UNIQUE constraint failed: favorite_stops.stop_id" }
        added.add(stop)
        favorites.add(stop)
    }

    override suspend fun removeFavorite(stopId: String) {
        saveFailure?.let { throw it }
        removed.add(stopId)
        favorites.removeAll { it.id == stopId }
    }

    override suspend fun getStations(): List<Station> {
        stationCalls++
        catalogFailure?.let { throw it }
        return stations
    }

    override suspend fun getLinesByStop(stopId: String): List<StopLine> {
        catalogFailure?.let { throw it }
        return lines[stopId].orEmpty()
    }

    /** Stands in for writing a favorite's changes, when set */
    var saving: (suspend () -> Unit)? = null

    override suspend fun updateFavorite(stop: FavoriteStop) {
        saving?.invoke()
        saveFailure?.let { throw it }
        saved.add(stop)
    }

    override suspend fun nextPosition() = (favorites.maxOfOrNull { it.position } ?: -1) + 1

    /** Every arrivals request for a trip: the stops and the age allowed */
    val tripAsked = mutableListOf<Pair<List<LineStop>, Duration>>()

    /** What the arrivals for a trip answer, from the stops asked about */
    var tripArrivals: suspend (List<LineStop>) -> LiveArrivals = { LiveArrivals.NONE }

    override suspend fun arrivalsFor(stops: List<LineStop>, maxAge: Duration): LiveArrivals {
        tripAsked.add(stops to maxAge)
        return tripArrivals(stops)
    }

    /** Thrown once every route is done, when set */
    var allRoutesFailure: Throwable? = null

    /** Stands in for the last of loading every route, once each is told done, when set */
    var allRoutesAnswer: (suspend () -> Unit)? = null
    var allRoutesCalls = 0

    /** Every route, [routes], each told done in turn */
    override suspend fun getAllLineStops(onProgress: ((done: Int, total: Int) -> Unit)?): Map<String, List<LineStop>> {
        allRoutesCalls++
        routes.keys.forEachIndexed { i, _ -> onProgress?.invoke(i + 1, routes.size) }
        allRoutesAnswer?.invoke()
        allRoutesFailure?.let { throw it }
        return routes
    }

    var headways: Map<String, Duration> = emptyMap()
    var rideFactors: Map<String, Double> = emptyMap()
    val observedRides = mutableListOf<Map<String, Double>>()

    /** Stands in for reading what is learned of the lines, when set */
    var learnedAnswer: (suspend () -> Unit)? = null

    override suspend fun lineHeadways(at: Long): Map<String, Duration> {
        learnedAnswer?.invoke()
        return headways
    }

    override suspend fun observeRides(readings: Map<String, Double>) {
        observedRides.add(readings)
    }

    override suspend fun rideFactors() = rideFactors

    // The lines: the catalog, routes, buses and hours, each counted

    var transitLines: List<TransitLine> = emptyList()
    var lineCalls = 0

    /** Routes by line name; a line missing here has an empty route */
    var routes: Map<String, List<LineStop>> = emptyMap()
    val routesAsked = mutableListOf<String>()

    /** Thrown by the routes, when set */
    var routeFailure: Throwable? = null

    /** Stands in for asking for a route, when set */
    var routeAnswer: (suspend () -> Unit)? = null

    /** Buses by line name, as the next answer gives them */
    var buses: Map<String, List<LineBus>> = emptyMap()
    val busesAsked = mutableListOf<String>()

    /** Thrown by the buses, when set */
    var busFailure: Throwable? = null

    /** Stands in for asking for the buses, when set */
    var busAnswer: (suspend () -> Unit)? = null

    var hours: Map<String, LineHours> = emptyMap()
    var hourCalls = 0

    /** Thrown by the hours, when set */
    var hourFailure: Throwable? = null

    override suspend fun getLines(): List<TransitLine> {
        lineCalls++
        catalogFailure?.let { throw it }
        return transitLines
    }

    override suspend fun getLineStops(line: String): List<LineStop> {
        routesAsked.add(line)
        routeAnswer?.invoke()
        routeFailure?.let { throw it }
        return routes[line].orEmpty()
    }

    override suspend fun getLineBuses(line: String): List<LineBus> {
        busesAsked.add(line)
        busAnswer?.invoke()
        busFailure?.let { throw it }
        return buses[line].orEmpty()
    }

    override suspend fun getLineHours(): Map<String, LineHours> {
        hourCalls++
        hourFailure?.let { throw it }
        return hours
    }

    override suspend fun recentArrivals(maxAge: Duration) = kept

    override suspend fun keepArrivals(arrivals: Map<String, List<BusArrival>>) {
        kept = arrivals
    }
}

/**
 * The device location, set by hand: whether location is on, the
 * permission and what asking for it answers, the last fix and the fresh
 * one. Records what was asked for.
 */
class FakeLocationSource(
    var enabled: Boolean = true,
    var permission: LocationPermission = LocationPermission.DENIED,
    /** What the prompt answers; the permission becomes it */
    var answer: LocationPermission = LocationPermission.GRANTED,
    var lastKnown: GeoPoint? = null,
    var current: GeoPoint? = null,
) : LocationSource {
    var requests = 0
    val currentAsked = mutableListOf<Pair<LocationAccuracy, Duration>>()

    /** Stands in for waiting on a fix, when set */
    var waiting: (suspend () -> Unit)? = null

    override fun isServiceEnabled() = enabled

    override fun checkPermission() =
        if (permission == LocationPermission.DENIED_FOREVER) LocationPermission.DENIED else permission

    override suspend fun requestPermission(): LocationPermission {
        requests++
        permission = answer
        return answer
    }

    override suspend fun lastKnownPosition(): GeoPoint? {
        if (permission != LocationPermission.GRANTED) throw LocationPermissionException()
        return lastKnown
    }

    override suspend fun currentPosition(accuracy: LocationAccuracy, timeLimit: Duration): GeoPoint {
        currentAsked.add(accuracy to timeLimit)
        if (permission != LocationPermission.GRANTED) throw LocationPermissionException()
        if (!enabled) throw LocationServiceDisabledException()
        waiting?.invoke()
        return current ?: throw LocationTimeoutException(timeLimit)
    }
}

/** A screen's own lifecycle, moved by hand: covered, uncovered, away */
class TestLifecycleOwner(initial: Lifecycle.State = Lifecycle.State.RESUMED) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = initial }
    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

fun favorite(id: String, latitude: Double, longitude: Double) = FavoriteStop(
    id = id,
    stopId = id,
    name = "Stop $id",
    anchorLatitude = latitude,
    anchorLongitude = longitude,
)

fun nearbyStop(id: String, arrivals: List<BusArrival> = emptyList()) =
    NearbyStop(id = id, name = "Stop $id", distanceMeters = 10.0, arrivals = arrivals)
