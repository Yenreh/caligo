package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.data.location.LocationAccuracy
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.data.location.LocationPermissionException
import com.yenreh.caligo.data.location.LocationSource
import com.yenreh.caligo.data.location.currentDevicePosition
import com.yenreh.caligo.domain.ARRIVALS_REACH_METERS
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.areasCovering
import com.yenreh.caligo.domain.distanceBetween
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.metersBetween
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.loadCatching
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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

/** How saving a favorite ended */
sealed interface FavoriteSave {
    data object Saved : FavoriteSave

    /**
     * A favorite already has that id: the area itself, or a favorite
     * saved from that stop and relinked to another since
     */
    data class Taken(val favorite: FavoriteStop) : FavoriteSave

    /** Not written, as when the disk fails */
    data object Failed : FavoriteSave
}

/** Stops around the user, with the position they were found from */
data class NearbyResult(val stops: List<NearbyStop>, val latitude: Double, val longitude: Double)

/**
 * Favorite stops and their upcoming buses, the Flutter app's
 * StopsNotifier, with the lookups its providers make for the stop
 * screens: around the device, around a point, the station catalog, the
 * lines of a stop.
 *
 * In the order the user saved them, or closest first when the settings
 * ask for it and the location is already allowed: only the nearby search
 * is worth a permission prompt.
 *
 * One per activity, shared by the screens that list stops. Each action
 * returns the [Job] doing it, as the Dart methods return their Future.
 * A save that fails leaves the stops as they were: in the Flutter app it
 * was an error nobody caught.
 */
class StopsViewModel(
    private val repository: StopsRepository,
    /** Waited for before the first read: the app's startup work */
    private val ready: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Each time the saved stops change elsewhere, as an import does */
    reloads: Flow<Unit> = emptyFlow(),
    private val location: LocationSource = NoLocation,
    /** The app's settings, for the order of the stops */
    private val settings: StateFlow<Settings> = MutableStateFlow(Settings(isLoading = false)),
) : ViewModel() {
    private val state = MutableStateFlow(StopsState(isLoading = true))
    val stops: StateFlow<StopsState> = state.asStateFlow()

    private var timer: Job? = null
    private var lastRefresh: Long? = null

    /** The positions a move is saving, the last one asked for */
    private var reorderSaving: Job? = null

    /** A fresh fix on its way, to put the closest stops first */
    private var locating: Job? = null

    /**
     * Screens following the arrivals while on show, each wanting only the
     * stops the home screen lists or all of them
     */
    private val watchers = LinkedHashMap<Any, Boolean>()

    private val autoRefreshing get() = watchers.isNotEmpty()

    /** Only the home screen is watching: it asks for the stops it lists */
    private val homeOnly get() = watchers.isNotEmpty() && watchers.values.all { it }

    init {
        // Reorder in place when the setting flips (or finishes loading):
        // the arrivals do not change with the order
        viewModelScope.launch {
            settings.map { it.sortStopsByProximity }.distinctUntilChanged().drop(1).collect { reorder() }
        }
        viewModelScope.launch {
            ready()
            load()
            reloads.collect { load() }
        }
    }

    override fun onCleared() {
        watchers.clear()
        timer?.cancel()
    }

    /** Load the saved stops and their arrivals */
    fun loadFavorites(): Job = viewModelScope.launch { load() }

    /**
     * Read the saved stops and show them, then refresh their arrivals.
     *
     * Whatever changes while the read or the position is on its way, or
     * a move still being saved, has the stops read again: the Flutter app
     * showed what it had read before waiting, and lost those changes.
     */
    private suspend fun load() {
        state.update { it.copy(isLoading = true, error = null) }
        try {
            var position: GeoPoint? = null
            var located = false
            while (true) {
                reorderSaving?.join()
                val before = state.value.favorites
                val read = repository.getFavorites()
                // Only two stops or more have an order worth the location
                if (!located && read.size >= 2) {
                    located = true
                    position = proximityPosition()
                }
                val favorites = arranged(read, position)
                val kept = if (state.value.arrivals.isEmpty()) keptArrivals(favorites) else null
                if (state.value.favorites !== before) continue
                state.update {
                    it.copy(favorites = favorites, arrivals = kept ?: it.arrivals, isLoading = false)
                }
                break
            }
            if (state.value.favorites.isNotEmpty()) refresh(force = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            state.update { it.copy(error = e.asApiException(), isLoading = false) }
        }
    }

    /**
     * Order the stops on screen again, as the setting now asks. An order
     * that waits for a fix applies to the stops as they are once it comes,
     * so changes made meanwhile stay, unlike in the Flutter app.
     */
    private suspend fun reorder() {
        if (state.value.favorites.size < 2) return
        val position = proximityPosition()
        state.update { it.copy(favorites = arranged(it.favorites, position)) }
    }

    /** Saved order, or closest to [position] first when there is one */
    private fun arranged(stops: List<FavoriteStop>, position: GeoPoint?): List<FavoriteStop> {
        if (position == null) return stops.sortedBy { it.position }
        return stops.sortedBy {
            distanceBetween(position.latitude, position.longitude, it.anchorLatitude, it.anchorLongitude)
        }
    }

    /**
     * Where to put the closest stops first from, when the settings ask for
     * it and the location is already allowed; null for the saved order.
     * Without a last fix the saved order shows meanwhile, and the stops
     * are ordered once a fresh one comes: the Flutter app held the list
     * back until then, up to [PROXIMITY_TIME_LIMIT].
     *
     * Never asks for the permission here: the dashboard has to work
     * without it, and only the nearby search is worth a prompt.
     */
    private suspend fun proximityPosition(): GeoPoint? {
        if (!settings.value.sortStopsByProximity) return null
        return try {
            if (location.checkPermission() != LocationPermission.GRANTED) return null
            location.lastKnownPosition().also { if (it == null) orderOnceLocated() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Location is optional here: keep the order the user saved
            null
        }
    }

    /** Ask for a rough fix, and put the closest stops first once it comes */
    private fun orderOnceLocated() {
        if (locating?.isActive == true) return
        locating = viewModelScope.launch {
            val position = try {
                location.currentPosition(LocationAccuracy.LOW, PROXIMITY_TIME_LIMIT)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@launch
            }
            // On the stops as they are by then; unless the setting changed meanwhile
            if (!settings.value.sortStopsByProximity) return@launch
            state.update { it.copy(favorites = arranged(it.favorites, position)) }
        }
    }

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
                                    refreshGroup(group, results, healed) { e -> failure = failure ?: e.asApiException() }
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

    /**
     * Ask for [group] and hand each favorite its arrivals. A favorite's own
     * second request failing goes to [failed] and leaves that favorite as
     * it was; the rest of the group still takes the shared answer, where
     * the Flutter app left it all stale.
     */
    private suspend fun refreshGroup(
        group: Group,
        results: MutableMap<String, List<BusArrival>>,
        healed: MutableMap<String, FavoriteStop>,
        failed: (Exception) -> Unit,
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
                try {
                    seen = repository.getNearbyStops(favorite.anchorLatitude, favorite.anchorLongitude)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Says nothing about the stop itself
                    failed(e)
                    continue
                }
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
     * answer does not cover it: for an area, when nothing at all answers
     * around it, which the Flutter app never told, so an area never
     * counted as missing
     */
    private fun arrivalsFor(favorite: FavoriteStop, nearby: List<NearbyStop>): List<BusArrival>? {
        if (favorite.isArea) {
            if (nearby.isEmpty()) return null
            return nearby.flatMap { it.arrivals }.sortedBy { it.arrivalTime }
        }
        return nearby.firstOrNull { it.id == favorite.stopId }?.arrivals
    }

    /** Point a favorite at another stop, keeping its label and place */
    fun relinkFavorite(id: String, stop: NearbyStop): Job = viewModelScope.launch {
        val favorite = state.value.favorites.firstOrNull { it.id == id } ?: return@launch
        if (!saved { repository.updateFavorite(favorite.copy(stopId = stop.id, name = stop.name, missingCount = 0)) }) {
            return@launch
        }
        load()
    }

    /**
     * Save a stop, or an area when [stopId] is null. Saved once written;
     * the stops then load again with their arrivals behind it, so a screen
     * can close at once, where the Flutter one waited for the arrivals too.
     * An id already saved is [FavoriteSave.Taken], which the Flutter app
     * met as a save failing silently.
     */
    fun addFavorite(
        id: String,
        name: String,
        anchorLatitude: Double,
        anchorLongitude: Double,
        stopId: String? = null,
        customName: String? = null,
    ): Deferred<FavoriteSave> = viewModelScope.async {
        var taken: FavoriteStop? = null
        if (!saved { taken = repository.getFavorites().firstOrNull { it.id == id } }) return@async FavoriteSave.Failed
        taken?.let { return@async FavoriteSave.Taken(it) }
        val added = saved {
            val position = repository.nextPosition()
            repository.addFavorite(
                FavoriteStop(
                    id = id,
                    name = name,
                    anchorLatitude = anchorLatitude,
                    anchorLongitude = anchorLongitude,
                    position = position,
                    stopId = stopId,
                    customName = customName?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        if (added) viewModelScope.launch { load() }
        if (added) FavoriteSave.Saved else FavoriteSave.Failed
    }

    /**
     * Change what the user chose for a stop: its own label, dropped when
     * [customName] is empty, and the lines it shows, all when empty
     */
    fun editFavorite(id: String, customName: String?, lines: List<String>): Job = viewModelScope.launch {
        val stop = state.value.favorites.firstOrNull { it.id == id } ?: return@launch
        val updated = stop.copy(customName = customName?.trim()?.takeIf { it.isNotEmpty() }, lines = lines)
        if (!saved { repository.updateFavorite(updated) }) return@launch
        // Nothing to fetch: the arrivals kept already hold every line
        state.update { current -> current.copy(favorites = current.favorites.map { if (it.id == id) updated else it }) }
    }

    /**
     * Move a favorite as a drag leaves it, with the indexes a reorderable
     * list reports. Only meaningful in the saved order: sorted by
     * proximity, the next load would undo it. The list shows the new
     * order at once; the positions are saved after.
     */
    fun reorderFavorites(oldIndex: Int, newIndex: Int): Job {
        val stops = state.value.favorites.toMutableList()
        val moved = stops.removeAt(oldIndex)
        stops.add(if (newIndex > oldIndex) newIndex - 1 else newIndex, moved)

        val numbered = stops.mapIndexed { i, stop -> stop.copy(position = i) }
        state.update { it.copy(favorites = numbered) }

        // A load waits for these saves: read before them, it would undo the move
        val earlier = reorderSaving
        return viewModelScope.launch {
            earlier?.join()
            saved {
                for ((i, stop) in numbered.withIndex()) {
                    if (stops[i].position != i) repository.updateFavorite(stop)
                }
            }
        }.also { reorderSaving = it }
    }

    /** List a stop on the home screen, or keep it to the stops screen */
    fun setShowOnHome(id: String, show: Boolean): Job = viewModelScope.launch {
        val stop = state.value.favorites.firstOrNull { it.id == id }
        if (stop == null || stop.showOnHome == show) return@launch

        val updated = stop.copy(showOnHome = show)
        if (!saved { repository.updateFavorite(updated) }) return@launch
        state.update { current -> current.copy(favorites = current.favorites.map { if (it.id == id) updated else it }) }
    }

    /** Remove a saved stop */
    fun removeFavorite(stopId: String): Job = viewModelScope.launch {
        if (!saved { repository.removeFavorite(stopId) }) return@launch
        state.update { current ->
            current.copy(
                favorites = current.favorites.filter { it.id != stopId },
                arrivals = current.arrivals - stopId,
            )
        }
    }

    /** Whether [save] went through; a failure is swallowed, as the Flutter app left it */
    private suspend fun saved(save: suspend () -> Unit): Boolean = try {
        save()
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    // What the Flutter providers beside the notifier look up

    /** Stops near the device, asking for the permission when needed: nearbyStopsProvider */
    suspend fun nearbyStops(): NearbyResult {
        val position = location.currentDevicePosition()
        val stops = repository.getNearbyStops(position.latitude, position.longitude)
        return NearbyResult(stops, position.latitude, position.longitude)
    }

    /** Stops around a point from a single request: nearbyAtPointProvider */
    suspend fun nearbyAt(latitude: Double, longitude: Double): List<NearbyStop> =
        repository.getNearbyStops(latitude, longitude)

    /** Lines serving a stop: linesByStopProvider */
    suspend fun linesByStop(stopId: String): List<StopLine> = repository.getLinesByStop(stopId)

    /** Fresh arrivals for a stop being looked at: refreshStopArrivals */
    suspend fun refreshStop(stop: NearbyStop, anchorLatitude: Double, anchorLongitude: Double): NearbyStop =
        refreshStopArrivals(repository, stop, anchorLatitude, anchorLongitude)

    private val stationsState = MutableStateFlow<Loaded<List<Station>>>(Loaded.Loading)
    private var stationsJob: Job? = null

    /**
     * The MIO station catalog, used to add favorites without GPS:
     * stationsProvider, which loads on first use, [watchStations], and
     * keeps its answer, even a failure, for the app's life until
     * [reloadStations]
     */
    val stations: StateFlow<Loaded<List<Station>>> = stationsState.asStateFlow()

    /** Load the catalog unless it already is */
    fun watchStations() {
        if (stationsJob == null) reloadStations()
    }

    /** Ask for the catalog again, the last answer on show meanwhile */
    fun reloadStations() {
        stationsJob?.cancel()
        stationsJob = viewModelScope.launch { stationsState.value = loadCatching { repository.getStations() } }
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

        /** No fix at once for the order: the closest first wait no longer for one */
        private val PROXIMITY_TIME_LIMIT = 8.seconds

        /** As the Flutter app reports an error that is not an ApiException */
        private fun Exception.asApiException() = this as? ApiException ?: ApiException(toString())

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                StopsViewModel(
                    container.stopsRepository,
                    ready = { container.startup.join() },
                    reloads = container.backup.imported,
                    location = container.location,
                    settings = container.settings.settings,
                )
            }
        }
    }
}

/**
 * Fresh arrivals for a stop being looked at, from a single request.
 *
 * Asks around the stop itself when its position is known, and around
 * the point it was found from otherwise. The service leaves out stops
 * with no bus on the way, so a stop missing from the answer has none.
 */
suspend fun refreshStopArrivals(
    repository: StopsRepository,
    stop: NearbyStop,
    anchorLatitude: Double,
    anchorLongitude: Double,
): NearbyStop {
    val nearby = repository.getNearbyStops(stop.latitude ?: anchorLatitude, stop.longitude ?: anchorLongitude)
    val match = nearby.firstOrNull { it.id == stop.id }
    return stop.copy(arrivals = match?.arrivals ?: emptyList())
}

/** No location at all: the permission never given */
private object NoLocation : LocationSource {
    override fun isServiceEnabled() = false

    override fun checkPermission() = LocationPermission.DENIED

    override suspend fun requestPermission() = LocationPermission.DENIED

    override suspend fun lastKnownPosition(): GeoPoint = throw LocationPermissionException()

    override suspend fun currentPosition(accuracy: LocationAccuracy, timeLimit: Duration): GeoPoint =
        throw LocationPermissionException()
}
