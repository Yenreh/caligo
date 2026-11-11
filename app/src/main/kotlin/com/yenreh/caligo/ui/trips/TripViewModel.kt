package com.yenreh.caligo.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.data.location.LocationSource
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.trips.TripControl
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.loadCatching
import com.yenreh.caligo.waits.NotificationAccess
import com.yenreh.caligo.waits.WaitControl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the trip did not start, with the way to the settings that fix it */
enum class TripMessage { NEEDS_NOTIFICATIONS, NEEDS_LOCATION, LOCATION_OFF }

/**
 * What a trip needs and does not have, after asking for it, from the
 * screen on show: its notification, as [trips] may post it, location on
 * and the while-in-use permission; null when all is there. A permission
 * already given as approximate alone is asked once more to be precise,
 * as the trip drops fixes that rough; refused, the trip starts all the
 * same, and its screen says why it goes by the estimate.
 */
suspend fun tripNeeds(notifications: NotificationAccess, location: LocationSource, trips: TripControl): TripMessage? {
    if (!notifications.granted() && !notifications.request()) return TripMessage.NEEDS_NOTIFICATIONS
    if (!trips.allowed()) return TripMessage.NEEDS_NOTIFICATIONS
    if (!location.isServiceEnabled()) return TripMessage.LOCATION_OFF
    var permission = location.checkPermission()
    if (permission == LocationPermission.GRANTED) {
        // Given before as approximate: the system's prompt to make it precise
        if (location.approximateOnly()) askLocation(location)
        return null
    }
    permission = askLocation(location)
    return if (permission == LocationPermission.GRANTED) null else TripMessage.NEEDS_LOCATION
}

private suspend fun askLocation(location: LocationSource): LocationPermission = try {
    location.requestPermission()
} catch (_: IllegalStateException) {
    // No activity to ask from
    LocationPermission.DENIED
}

/** What the trip screen shows before the trip runs, and whether it does */
data class TripScreenState(
    /** The ride to choose a stop on; Ready(null) when its line does not call at the stop */
    val plan: Loaded<TripPlan?> = Loaded.Loading,
    /** Chosen or skipped, and started: the screen follows the trip from here */
    val started: Boolean = false,
    /** Restored long after its ride, as after the process went: nothing to ask, the screen closes */
    val stale: Boolean = false,
    /** Asking for what starting needs */
    val starting: Boolean = false,
    val message: TripMessage? = null,
)

/**
 * The trip screen's state: the ride [took] names, on its line's route,
 * until the rider says where they get off or skips; then the trip, as
 * the trip service follows it. Without [took], the trip running.
 *
 * Opened by "I took it", it ends that wait as taken. The trip starts here,
 * with the app on show, after the notification and location permissions
 * it needs: while in use is enough, so no background location is asked.
 * Restored with a ride over [STALE_TOOK] old that no trip follows, as the
 * back stack is after the process went, it asks nothing and closes.
 */
class TripViewModel(
    val took: TookRide?,
    private val repository: StopsRepository,
    val trips: TripControl,
    waits: WaitControl,
    private val location: LocationSource,
    private val notifications: NotificationAccess,
    now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val state = MutableStateFlow(TripScreenState())
    val screen: StateFlow<TripScreenState> = state.asStateFlow()

    private var loading: Job? = null

    init {
        val running = trips.running.value
        val ended = trips.ended.value
        when {
            took == null -> state.update { it.copy(started = true) }
            // Back from the notification, or turned: the trip already runs, or ran
            running?.ride?.plan?.took == took || ended?.journey?.ride?.plan?.took == took -> state.update { it.copy(started = true) }
            // Its ride long gone: neither asked about nor taken
            now() - took.trip > STALE_TOOK.inWholeMilliseconds -> state.update { it.copy(stale = true) }
            // A bus a planner trip waited for: the trip takes it as its ride
            trips.took(took) -> state.update { it.copy(started = true) }
            else -> load()
        }
        if (!state.value.stale) took?.let { waits.took(it.waitKey) }
    }

    /** Ask for the route again */
    fun retry() {
        if (took == null) return
        state.update { it.copy(plan = Loaded.Loading) }
        load()
    }

    private fun load() {
        val took = took ?: return
        loading?.cancel()
        loading = viewModelScope.launch {
            val route = loadCatching { repository.getLineStops(took.line) }
            val factor = try {
                repository.rideFactors()[took.line] ?: 1.0
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                1.0
            }
            state.update { s ->
                s.copy(
                    plan = when (route) {
                        is Loaded.Ready -> Loaded.Ready(TripPlan.of(took, route.value, factor))
                        is Loaded.Failed -> route
                        Loaded.Loading -> Loaded.Loading
                    },
                )
            }
        }
    }

    /** Get off at [alight], or just follow the ride when null: start the trip */
    fun choose(alight: Int?) {
        val plan = state.value.plan.valueOrNull ?: return
        if (state.value.starting || state.value.started) return
        state.update { it.copy(starting = true, message = null) }
        viewModelScope.launch {
            val missing = missing()
            if (missing != null) {
                state.update { it.copy(starting = false, message = missing) }
                return@launch
            }
            trips.start(plan.copy(alight = alight))
            state.update { it.copy(starting = false, started = true) }
        }
    }

    private suspend fun missing(): TripMessage? = tripNeeds(notifications, location, trips)

    /** Get off at [alight] instead, while the trip runs */
    fun alightAt(alight: Int?) = trips.alightAt(alight)

    /** End the trip: Undo takes it back for a while */
    fun finish() = trips.finish()

    fun undoFinish() = trips.undoFinish()

    /** "I took the [line]" on the screen, while a planner trip waits at the stop */
    fun takeLine(line: String) = trips.takeLine(line)

    /** "No" as a bus of [line] left: back to waiting for the next */
    fun notTaken(line: String) = trips.notTaken(line)

    /** Take the ride ahead on [line] alone, or on any of its lines when null */
    fun chooseLine(line: String?) = trips.chooseLine(line)

    fun dismissMessage() = state.update { it.copy(message = null) }

    /** The notification settings, for the message that asks for them: the trips' channel, or the app's */
    fun openNotificationSettings() {
        dismissMessage()
        trips.openSettings()
    }

    companion object {
        /** A ride taken this long ago is gone */
        val STALE_TOOK = 30.minutes

        fun factory(took: TookRide?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                TripViewModel(
                    took,
                    container.stopsRepository,
                    container.trips,
                    container.waits,
                    container.location,
                    container.notificationAccess,
                )
            }
        }
    }
}
