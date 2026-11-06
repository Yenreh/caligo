package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.domain.ARRIVALS_REACH_METERS
import com.yenreh.caligo.domain.areasCovering
import com.yenreh.caligo.domain.distanceBetween
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.metersBetween
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The favorite stops and their upcoming buses */
data class StopsState(
    val favorites: List<FavoriteStop> = emptyList(),
    /** By favorite id; a favorite missing here has no answer yet */
    val arrivals: Map<String, List<BusArrival>> = emptyMap(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: ApiException? = null,
    /** Epoch milliseconds */
    val lastUpdated: Long? = null,
)

/**
 * Favorite stops and their upcoming buses: the part of the Flutter app's
 * StopsNotifier the home screen uses. Adding, editing, reordering and
 * relinking come with the stops screen.
 *
 * Kept in the order the user saved them. Closest first, the default
 * setting, needs the device location, which comes in phase 4; until then
 * the saved order stands, as in the Flutter app without the permission.
 *
 * One per activity, shared by the screens that list stops. Each action
 * returns the [Job] doing it, as the Dart methods return their Future.
 */
class StopsViewModel(
    private val repository: StopsRepository,
    /** Waited for before the first read: the app's startup work */
    private val ready: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val state = MutableStateFlow(StopsState(isLoading = true))
    val stops: StateFlow<StopsState> = state.asStateFlow()

    private var timer: Job? = null
    private var lastRefresh: Long? = null

    /**
     * Screens following the arrivals while on show, each wanting only the
     * stops the home screen lists or all of them
     */
    private val watchers = LinkedHashMap<Any, Boolean>()

    private val autoRefreshing get() = watchers.isNotEmpty()

    /** Only the home screen is watching: it asks for the stops it lists */
    private val homeOnly get() = watchers.isNotEmpty() && watchers.values.all { it }

    init {
        viewModelScope.launch {
            ready()
            load()
        }
    }

    override fun onCleared() {
        watchers.clear()
        timer?.cancel()
    }

    /** Load the saved stops and their arrivals */
    fun loadFavorites(): Job = viewModelScope.launch { load() }

    private suspend fun load() {
        state.update { it.copy(isLoading = true, error = null) }
        try {
            val favorites = ordered(repository.getFavorites())
            val kept = if (state.value.arrivals.isEmpty()) keptArrivals(favorites) else null
            state.update {
                it.copy(favorites = favorites, arrivals = kept ?: it.arrivals, isLoading = false)
            }
            if (favorites.isNotEmpty()) refresh(force = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            state.update { it.copy(error = e.asApiException(), isLoading = false) }
        }
    }

    /** The saved order; closest first needs the location, in phase 4 */
    private fun ordered(stops: List<FavoriteStop>) = stops.sortedBy { it.position }

    /**
     * Arrivals from the last run, without the buses already gone. A list
     * left empty is dropped: it would claim no bus is coming when the
     * truth is not known yet.
     */
    private suspend fun keptArrivals(favorites: List<FavoriteStop>): Map<String, List<BusArrival>> {
        val kept = repository.recentArrivals(KEPT_ARRIVALS_MAX_AGE) ?: return emptyMap()
        val now = now()
        val ids = favorites.map { it.id }.toSet()
        return buildMap {
            for ((id, buses) in kept) {
                if (id !in ids) continue
                val upcoming = buses.filter { it.arrivalTime >= now }
                if (upcoming.isNotEmpty()) put(id, upcoming)
            }
        }
    }

    /** The stops to ask about: those the home lists, when it alone watches */
    private val targets: List<FavoriteStop>
        get() = state.value.favorites.let { all -> if (homeOnly) all.filter { it.showOnHome } else all }

    /**
     * Refresh the arrivals of every saved stop, or of those the home lists
     * when it alone watches.
     *
     * Favorites one request can answer share it, as [groupFavorites]
     * makes them; a stop the shared answer does not mention is asked for
     * on its own when that could tell more.
     */
    fun refreshArrivals(force: Boolean = false): Job = viewModelScope.launch { refresh(force) }

    private suspend fun refresh(force: Boolean) {
        val targets = targets
        val current = state.value
        if (current.isRefreshing || targets.isEmpty()) return

        val last = lastRefresh
        // Too soon after the last one, unless some stop has nothing to show
        // yet, such as those the home screen leaves out
        if (!force && last != null && now() - last < MIN_REFRESH_GAP.inWholeMilliseconds &&
            targets.all { it.id in current.arrivals }
        ) {
            return
        }

        state.update { it.copy(isRefreshing = true, error = null) }

        val results = LinkedHashMap(current.arrivals)
        val healed = LinkedHashMap<String, FavoriteStop>()
        val saved = LinkedHashMap<String, FavoriteStop>()
        var failure: ApiException? = null
        var ended = false
        try {
            // Every error ends the refresh, of whatever type: the Flutter app
            // only caught ApiException, so anything else left it running
            try {
                val groups = groupFavorites(targets)
                for (batch in groups.chunked(MAX_CONCURRENT_REQUESTS)) {
                    coroutineScope {
                        batch.map { group ->
                            async {
                                try {
                                    refreshGroup(group, results, healed)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    // A failed request says nothing about the stops themselves
                                    failure = failure ?: e.asApiException()
                                }
                            }
                        }.awaitAll()
                    }
                }
                for (stop in healed.values) {
                    repository.updateFavorite(stop)
                    saved[stop.id] = stop
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = failure ?: e.asApiException()
            }

            lastRefresh = now()
            state.update {
                it.copy(
                    favorites = if (saved.isEmpty()) it.favorites else it.favorites.map { stop -> saved[stop.id] ?: stop },
                    arrivals = results,
                    isRefreshing = false,
                    error = failure,
                    lastUpdated = now(),
                )
            }
            ended = true
        } finally {
            // Cut short, by cancellation or worse: still over
            if (!ended) state.update { it.copy(isRefreshing = false) }
        }

        if (failure == null) viewModelScope.launch { repository.keepArrivals(results) }
        // A refresh from anywhere resets the clock, and sets its new pace
        if (autoRefreshing) scheduleNext()
    }

    private suspend fun refreshGroup(
        group: Group,
        results: MutableMap<String, List<BusArrival>>,
        healed: MutableMap<String, FavoriteStop>,
    ) {
        val nearby = repository.getNearbyStops(group.latitude, group.longitude)

        for (favorite in group.stops) {
            var arrivals = arrivalsFor(favorite, nearby)
            var seen = nearby

            // A placed stop within reach that the answer leaves out has no
            // bus coming. One asked about around an anchor is worth asking
            // again only when anchored somewhere else: the same point gives
            // the same answer.
            val elsewhere = !group.placed &&
                distanceBetween(group.latitude, group.longitude, favorite.anchorLatitude, favorite.anchorLongitude) > 1

            if (arrivals == null && elsewhere) {
                seen = repository.getNearbyStops(favorite.anchorLatitude, favorite.anchorLongitude)
                arrivals = arrivalsFor(favorite, seen)
            }

            results[favorite.id] = arrivals ?: emptyList()

            reconcile(favorite, seen, found = arrivals != null)?.let { healed[favorite.id] = it }
        }
    }

    private fun scheduleNext() {
        timer?.cancel()
        // Paced by the buses the user follows, not the whole stop
        val arrivals = state.value.arrivals
        val wait = nextRefreshDelay(targets.map { it.pick(arrivals[it.id] ?: emptyList()) }, now())
        timer = viewModelScope.launch {
            delay(wait)
            // Fired: the refresh below may schedule the next one itself
            timer = null
            refresh(force = false)
            // Skipped refreshes (too soon, already running) still keep the pace
            if (autoRefreshing) scheduleNext()
        }
    }

    /**
     * Keep a favorite in step with what the service reports.
     *
     * Names drift when Metrocali relabels a stop, and a stop that stops
     * being reported has probably been retired, renumbered or moved out
     * of reach: counting the misses tells that apart from a quiet stop
     * with no bus on the way.
     */
    private fun reconcile(favorite: FavoriteStop, nearby: List<NearbyStop>, found: Boolean): FavoriteStop? {
        if (!found) {
            // An area is only gone when nothing at all answers around it
            if (favorite.isArea && nearby.isNotEmpty()) return null
            return if (favorite.missingCount >= FavoriteStop.MISSING_THRESHOLD) {
                null
            } else {
                favorite.copy(missingCount = favorite.missingCount + 1)
            }
        }

        val reportedName = if (favorite.isArea) {
            favorite.name
        } else {
            nearby.first { it.id == favorite.stopId }.name
        }

        val nameChanged = reportedName.isNotEmpty() && reportedName != favorite.name
        if (!nameChanged && favorite.missingCount == 0) return null

        return favorite.copy(name = if (nameChanged) reportedName else favorite.name, missingCount = 0)
    }

    /** Favorites one request answers, and the point it asks around */
    private class Group(
        val latitude: Double,
        val longitude: Double,
        val stops: List<FavoriteStop>,
        /** Asked around a point within reach of each of its stops */
        val placed: Boolean,
    )

    /**
     * Group favorites that a single request can answer together.
     *
     * A stop whose place is known can be asked about from anywhere within
     * reach of it, so those share requests the way the stops of a trip do.
     * An area, or a stop never placed, is asked about around its anchor,
     * with the others saved from nearly the same spot.
     */
    private suspend fun groupFavorites(stops: List<FavoriteStop>): List<Group> {
        val positions = repository.stopPositions(stops.mapNotNull { it.stopId })
        val placed = stops.mapNotNull { stop -> stop.stopId?.let { positions[it] }?.let { stop to it } }

        val groups = mutableListOf<Group>()
        val grouped = mutableSetOf<String>()
        for (centre in areasCovering(placed.map { it.second }, ARRIVALS_REACH_METERS)) {
            val within = placed.filter { (stop, at) ->
                stop.id !in grouped &&
                    metersBetween(centre.latitude, centre.longitude, at.latitude, at.longitude) <=
                    ARRIVALS_REACH_METERS
            }.map { it.first }
            grouped.addAll(within.map { it.id })
            groups.add(Group(centre.latitude, centre.longitude, within, placed = true))
        }

        val rest = stops.filter { it.id !in grouped }
        for (cluster in clusterByAnchor(rest)) {
            val head = cluster.first()
            groups.add(Group(head.anchorLatitude, head.anchorLongitude, cluster, placed = false))
        }
        return groups
    }

    /** Group favorites saved from nearly the same spot */
    private fun clusterByAnchor(stops: List<FavoriteStop>): List<List<FavoriteStop>> {
        val clusters = mutableListOf<MutableList<FavoriteStop>>()
        for (stop in stops) {
            val match = clusters.firstOrNull { cluster ->
                val head = cluster.first()
                distanceBetween(head.anchorLatitude, head.anchorLongitude, stop.anchorLatitude, stop.anchorLongitude) <=
                    CLUSTER_RADIUS_METRES
            }
            if (match != null) match.add(stop) else clusters.add(mutableListOf(stop))
        }
        return clusters
    }

    /**
     * Arrivals for [favorite] inside a shared answer, or null when that
     * answer does not cover it
     */
    private fun arrivalsFor(favorite: FavoriteStop, nearby: List<NearbyStop>): List<BusArrival>? {
        if (favorite.isArea) {
            return nearby.flatMap { it.arrivals }.sortedBy { it.arrivalTime }
        }
        return nearby.firstOrNull { it.id == favorite.stopId }?.arrivals
    }

    /**
     * Refresh arrivals while [watcher], a screen listing them, is on show,
     * at the pace [nextRefreshDelay] sets.
     *
     * [homeOnly] is the home screen, which lists only some stops; as long
     * as another screen watches too, every stop is asked for.
     */
    fun startAutoRefresh(watcher: Any, homeOnly: Boolean = false) {
        watchers[watcher] = homeOnly
        scheduleNext()
    }

    /** [watcher] went out of sight; the refresh stops with the last one */
    fun stopAutoRefresh(watcher: Any) {
        watchers.remove(watcher)
        if (watchers.isNotEmpty()) return
        timer?.cancel()
        timer = null
    }

    fun clearError() = state.update { it.copy(error = null) }

    companion object {
        /**
         * The arrivals service takes one request per point, so keep a few
         * in flight at a time instead of firing all of them at once
         */
        private const val MAX_CONCURRENT_REQUESTS = 4

        /**
         * One request covers every stop within 300 m of the point it asks
         * about, so favorites saved from nearly the same place share it
         * when their stops have no known place
         */
        private const val CLUSTER_RADIUS_METRES = 30.0

        /** Arrivals move slowly enough that answering twice in a few seconds only wastes data */
        private val MIN_REFRESH_GAP = 15.seconds

        /**
         * Arrivals kept from the last run are shown on launch while fresh
         * ones load, as long as they are this recent: they hold absolute
         * times, so their countdowns stay right until buses start to drift
         */
        private val KEPT_ARRIVALS_MAX_AGE = 5.minutes

        /**
         * How long to wait before asking again, from how soon the next bus
         * comes: a countdown near zero is worth following closely, one
         * twenty minutes out barely moves between two looks.
         */
        fun nextRefreshDelay(arrivals: Iterable<List<BusArrival>>, now: Long = System.currentTimeMillis()): Duration {
            var soonest: Int? = null
            for (list in arrivals) {
                for (bus in list) {
                    if (bus.arrivalTime < now) continue
                    val minutes = bus.minutesUntilArrival(now)
                    if (soonest == null || minutes < soonest) soonest = minutes
                }
            }
            return when {
                soonest != null && soonest <= 5 -> 30.seconds
                soonest != null && soonest <= 15 -> 60.seconds
                else -> 120.seconds
            }
        }

        /** As the Flutter app reports an error that is not an ApiException */
        private fun Exception.asApiException() = this as? ApiException ?: ApiException(toString())

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                StopsViewModel(container.stopsRepository, ready = { container.startup.join() })
            }
        }
    }
}
