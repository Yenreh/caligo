package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.json.doubleOrNull
import com.yenreh.caligo.data.json.isNum
import com.yenreh.caligo.data.location.LocationSource
import com.yenreh.caligo.data.location.currentDevicePosition
import com.yenreh.caligo.data.location.lastKnownDevicePosition
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.distanceBetween
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.ui.common.AutoRetry
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.ProviderRetry
import com.yenreh.caligo.ui.common.RetryRule
import com.yenreh.caligo.ui.map.LabMapState
import com.yenreh.caligo.ui.map.MapCamera
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** What the map screen shows, besides the map itself */
data class MapStopsState(
    /** Where the map opens; null for the moment it takes to work out */
    val map: LabMapState? = null,
    /** The point last searched around; null before any search */
    val searched: GeoPoint? = null,
    /** The stops around [searched]: the list first, then placed */
    val results: Loaded<List<NearbyStop>>? = null,
    val selectedStopId: String? = null,
    /** Waiting for the fix the location button asked for, which it shows */
    val locating: Boolean = false,
)

/**
 * The Flutter MapStopsScreen's state: pick any place on the map and
 * inspect the stops around it.
 *
 * The map opens on the last known device position, then on where it was
 * left, and only then on the city centre: opening on the centre and
 * jumping to the user once located fetched two areas every time. A fresh
 * fix then moves it, unless it lands close to where it opened.
 *
 * One per add-stop screen, kept while its tabs change and the screen
 * turns.
 */
class MapStopsViewModel(
    private val repository: StopsRepository,
    private val location: LocationSource,
    private val cache: JsonCache,
    /** Outlives the screen: the camera is saved on it as the map goes */
    private val work: CoroutineScope,
    /** When a failed search is tried again by itself, as Riverpod retries a provider */
    private val retry: RetryRule = ProviderRetry.default,
) : ViewModel() {
    private val state = MutableStateFlow(MapStopsState())
    val screen: StateFlow<MapStopsState> = state.asStateFlow()

    private var search: Job? = null

    /** The retries of the point last searched around, which has a provider of its own */
    private var searchRetry: AutoRetry? = null

    /** The quiet fix on opening, while it is on its way */
    private var quiet: Job? = null

    /** Where the map opened, to tell whether it has moved since */
    private var opened: MapCamera? = null

    init {
        viewModelScope.launch { openWhereUseful() }
    }

    private suspend fun openWhereUseful() {
        // Instant and GPS-free, but only with the permission already given
        val start = location.lastKnownDevicePosition()?.let { MapCamera(it, PLACE_ZOOM) }
            ?: savedCamera()
            ?: MapCamera(CALI_CENTER, CITY_ZOOM)
        opened = start
        state.update { it.copy(map = LabMapState(start, minZoom = MIN_ZOOM, maxZoom = MAX_ZOOM)) }
        centreOnDevice(askForPermission = false)
    }

    /** `[latitude, longitude, zoom]`, as the Flutter screen saved it */
    private suspend fun savedCamera(): MapCamera? {
        val saved = cache.read(CAMERA_KEY) as? JsonArray ?: return null
        // A list of anything but three numbers left the Flutter map blank:
        // here it is no saved camera
        if (saved.size != 3 || !saved.all { it.isNum }) return null
        val (latitude, longitude, zoom) = saved.map { it.doubleOrNull()!! }
        return MapCamera(GeoPoint(latitude, longitude), zoom)
    }

    /**
     * Keep where the map is, for the next time it opens, once it has been
     * moved: the Flutter map only saved a camera it had reported
     */
    fun saveCamera() {
        val camera = state.value.map?.camera ?: return
        if (camera == opened) return
        work.launch {
            cache.write(
                CAMERA_KEY,
                JsonArray(listOf(camera.center.latitude, camera.center.longitude, camera.zoom).map(::JsonPrimitive)),
            )
        }
    }

    /**
     * Move to where the user is, staying put when that is not known.
     *
     * The quiet call on opening, which never asks for the permission,
     * leaves the map alone when the fix lands near where it already is,
     * and the button still: the Flutter button spun for it, up to 15 s.
     * The button always moves, and takes over from a quiet call.
     */
    fun centreOnDevice(askForPermission: Boolean = true) {
        if (!askForPermission) {
            quiet = viewModelScope.launch { moveToDevice(quietly = true) }
            return
        }
        if (state.value.locating) return
        quiet?.cancel()
        state.update { it.copy(locating = true) }
        viewModelScope.launch {
            try {
                moveToDevice(quietly = false)
            } finally {
                state.update { it.copy(locating = false) }
            }
        }
    }

    private suspend fun moveToDevice(quietly: Boolean) {
        try {
            val position = location.currentDevicePosition(requestPermission = !quietly)
            val map = state.value.map ?: return
            val away = distanceBetween(
                map.camera.center.latitude,
                map.camera.center.longitude,
                position.latitude,
                position.longitude,
            )
            if (quietly && away < RECENTRE_METRES) return
            map.move(position, PLACE_ZOOM)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No location: the map stays where it opened
        }
    }

    /**
     * Look for stops around the point under the crosshair; a failure is
     * tried again by itself, loading meanwhile
     */
    fun searchHere() {
        val center = state.value.map?.camera?.center ?: return
        state.update { it.copy(searched = center, selectedStopId = null, results = Loaded.Loading) }
        searchRetry?.cancel()
        val retries = AutoRetry(viewModelScope, retry)
        searchRetry = retries
        searchAround(center, retries)
    }

    private fun searchAround(center: GeoPoint, retries: AutoRetry) {
        search?.cancel()
        search = viewModelScope.launch {
            repository.locatedStops(center.latitude, center.longitude)
                .catch { e ->
                    if (e is CancellationException) throw e
                    val retrying = retries.afterFailure(e) { searchAround(center, retries) }
                    state.update { it.copy(results = if (retrying) Loaded.Loading else Loaded.Failed(e)) }
                }
                .collect { stops -> state.update { it.copy(results = Loaded.Ready(stops)) } }
        }
    }

    /** Highlight a stop and bring the map to it */
    fun focus(stop: NearbyStop) {
        state.update { it.copy(selectedStopId = stop.id) }
        val map = state.value.map ?: return
        if (!stop.hasPosition) return
        val zoom = map.camera.zoom
        map.move(GeoPoint(stop.latitude!!, stop.longitude!!), if (zoom < 16) 17.0 else zoom)
    }

    companion object {
        /** Cali city centre, used when nothing better is known */
        val CALI_CENTER = GeoPoint(3.4516, -76.5320)

        /** Zoom the map opens at on a known position */
        const val PLACE_ZOOM = 16.0

        /** Zoom over the city centre */
        const val CITY_ZOOM = 15.0

        const val MIN_ZOOM = 11.0
        const val MAX_ZOOM = 18.0

        /**
         * A fresh fix closer than this to where the map opened leaves it be:
         * moving would only fetch the same area again
         */
        const val RECENTRE_METRES = 150.0

        const val CAMERA_KEY = "map_camera"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                MapStopsViewModel(container.stopsRepository, container.location, container.cache, container.scope)
            }
        }
    }
}
