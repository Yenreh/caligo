package com.yenreh.caligo.ui.lines

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.loadCatching
import com.yenreh.caligo.ui.map.LabMapState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the line screen shows, besides the map itself */
data class LineScreenState(
    /** The route, both directions */
    val stops: Loaded<List<LineStop>> = Loaded.Loading,
    /** The buses as last answered, Loading until the first answer since the screen came into view */
    val buses: Loaded<List<LineBus>> = Loaded.Loading,
    /** Asking for the buses with no answer to it yet: on coming into view, and again from the button */
    val updatingBuses: Boolean = true,
    /** The direction picked; null for the route's first */
    val chosenDirection: Int? = null,
    val selectedStopId: String? = null,
)

/** A route as the screen draws it: its directions and the one shown */
class ShownRoute(all: List<LineStop>, chosen: Int?) {
    /** Every direction the route has, in order; never empty */
    val directions: List<Int> = all.map { it.direction }.distinct().sorted()

    /** The direction shown: the one picked while the route has it, else its first */
    val direction: Int = if (chosen != null && chosen in directions) chosen else directions.first()

    /** The stops of [direction], in route order */
    val stops: List<LineStop> = all.filter { it.direction == direction }

    /** Where each of [directions] ends: its last stop's name */
    val ends: List<String> = directions.map { d -> all.last { it.direction == d }.name }

    val points: List<GeoPoint> get() = stops.map { GeoPoint(it.latitude, it.longitude) }
}

/**
 * The Flutter LineScreen's state with its providers: a line's route, one
 * direction at a time, the stop picked along it, and the buses on the
 * line, asked again [interval] after each answer while the screen is in view
 * (lineBusesProvider) and not at all while it is not.
 *
 * One per line screen, kept while it turns.
 */
class LineViewModel(
    val line: String,
    private val repository: StopsRepository,
    private val interval: Duration = LINE_BUSES_INTERVAL,
) : ViewModel() {
    private val state = MutableStateFlow(LineScreenState())
    val screen: StateFlow<LineScreenState> = state.asStateFlow()

    /** The map, once the screen has laid it out */
    var map: LabMapState? = null
        private set

    private var stopsJob: Job? = null
    private var round: Job? = null
    private var shown = false

    init {
        reloadStops()
    }

    /** Ask for the route again, the last answer on show meanwhile: lineStopsProvider invalidated */
    fun reloadStops() {
        stopsJob?.cancel()
        stopsJob = viewModelScope.launch {
            val answer = loadCatching { repository.getLineStops(line) }
            state.update { it.copy(stops = answer) }
        }
    }

    /**
     * The map for a [viewport] of that size, opening on the direction
     * shown, as flutter_map's initialCameraFit; the same one from then on
     */
    fun mapFor(viewport: DpSize): LabMapState {
        map?.let { return it }
        val all = state.value.stops.valueOrNull.orEmpty()
        check(all.isNotEmpty()) { "No route to show" }
        val route = ShownRoute(all, state.value.chosenDirection)
        return LabMapState.fitting(route.points, viewport, FIT_PADDING, MIN_ZOOM, MAX_ZOOM, rotatable = false)
            .also { map = it }
    }

    /**
     * Whether the screen is in view: out of view the buses are let go,
     * which stops asking for them; back in view they are asked for at once
     */
    fun setShown(shown: Boolean) {
        if (shown == this.shown) return
        this.shown = shown
        if (shown) {
            startRound()
        } else {
            round?.cancel()
            round = null
            state.update { it.copy(buses = Loaded.Loading, updatingBuses = true) }
        }
    }

    /** Start the round over: positions now, the next in [interval] */
    fun refreshBuses() {
        if (shown) startRound()
    }

    /**
     * Ask now, and [interval] after each answer; the buses on show stay
     * until the first new answer. One request at a time, and a round
     * started over cancels the one it replaces: the Flutter app's periodic
     * timer let a slow answer overlap the next, and land over a newer one.
     */
    private fun startRound() {
        round?.cancel()
        state.update { it.copy(updatingBuses = true) }
        round = viewModelScope.launch {
            while (true) {
                val answer = loadCatching { repository.getLineBuses(line) }
                state.update { it.copy(buses = answer, updatingBuses = false) }
                delay(interval)
            }
        }
    }

    /** Show [direction] and frame its stops, the stop picked let go */
    fun chooseDirection(direction: Int) {
        state.update { it.copy(chosenDirection = direction, selectedStopId = null) }
        val stops = state.value.stops.valueOrNull.orEmpty().filter { it.direction == direction }
        if (stops.size < 2) return
        map?.fit(stops.map { GeoPoint(it.latitude, it.longitude) }, FIT_PADDING)
    }

    /** Pick [stop] and bring the map to it, zoomed in to street level at least */
    fun open(stop: LineStop) {
        state.update { it.copy(selectedStopId = stop.stopId) }
        val map = map ?: return
        val zoom = map.camera.zoom
        map.move(GeoPoint(stop.latitude, stop.longitude), if (zoom < STOP_ZOOM) STOP_ZOOM else zoom)
    }

    companion object {
        /**
         * Buses move a block or two in this time: often enough to follow
         * them, seldom enough for a public service
         */
        val LINE_BUSES_INTERVAL = 30.seconds

        const val MIN_ZOOM = 11.0
        const val MAX_ZOOM = 18.0

        /** The zoom a stop opened is shown at, at least */
        const val STOP_ZOOM = 16.0

        /** Room around a route framed on the map */
        val FIT_PADDING = 40.dp

        fun factory(line: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                LineViewModel(line, container.stopsRepository)
            }
        }
    }
}
