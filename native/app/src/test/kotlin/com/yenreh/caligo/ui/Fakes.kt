package com.yenreh.caligo.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.CardBalance
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlin.time.Duration

/**
 * Cards kept in memory. The balance service fails for [failing] ids, as
 * the Dart test's fake; [failure] can make it throw anything else, and
 * [answer] stands in for the request itself. Saves land in [cards] and
 * are recorded; [saveFailure] makes them throw, and [saving] holds them.
 */
class FakeCardRepository(
    cards: List<CardEntity>,
    private val failing: Set<String> = emptySet(),
) : CardRepository {
    val cards = cards.toMutableList()
    val asked = mutableListOf<String>()
    var failure: Throwable? = null
    var answer: (suspend () -> Unit)? = null

    /** In the datasource's order: by position, then by name */
    override suspend fun getAllCards() = cards.sortedWith(compareBy<CardEntity> { it.position }.thenBy { it.name })

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

/** Repository that records how the arrivals were asked for */
class FakeStopsRepository(
    private val favorites: List<FavoriteStop>,
    private val nearby: List<NearbyStop>,
    /** Where the stops stand, for those whose place is known */
    private val positions: Map<String, GeoPoint> = emptyMap(),
) : StopsRepository {
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

    override suspend fun getFavorites() = favorites

    override suspend fun getNearbyStops(latitude: Double, longitude: Double): List<NearbyStop> {
        nearbyCalls++
        askedAround.add(GeoPoint(latitude, longitude))
        answer?.invoke()
        failure?.let { throw it }
        return nearby
    }

    override suspend fun stopPositions(stopIds: Iterable<String>): Map<String, GeoPoint> =
        stopIds.mapNotNull { id -> positions[id]?.let { id to it } }.toMap()

    override suspend fun addFavorite(stop: FavoriteStop) = Unit

    override suspend fun removeFavorite(stopId: String) = Unit

    override suspend fun updateFavorite(stop: FavoriteStop) {
        saveFailure?.let { throw it }
        saved.add(stop)
    }

    override suspend fun nextPosition() = 0

    override suspend fun arrivalsFor(stops: List<LineStop>, maxAge: Duration) = LiveArrivals.NONE

    override suspend fun recentArrivals(maxAge: Duration) = kept

    override suspend fun keepArrivals(arrivals: Map<String, List<BusArrival>>) {
        kept = arrivals
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
