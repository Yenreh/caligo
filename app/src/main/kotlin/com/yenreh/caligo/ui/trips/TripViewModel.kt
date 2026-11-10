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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the trip did not start, with the way to the settings that fix it */
enum class TripMessage { NEEDS_NOTIFICATIONS, NEEDS_LOCATION, LOCATION_OFF }

/** What the trip screen shows before the trip runs, and whether it does */
data class TripScreenState(
    /** The ride to choose a stop on; Ready(null) when its line does not call at the stop */
    val plan: Loaded<TripPlan?> = Loaded.Loading,
    /** Chosen or skipped, and started: the screen follows the trip from here */
    val started: Boolean = false,
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
 */
class TripViewModel(
    val took: TookRide?,
    private val repository: StopsRepository,
    val trips: TripControl,
    waits: WaitControl,
    private val location: LocationSource,
    private val notifications: NotificationAccess,
) : ViewModel() {
    private val state = MutableStateFlow(TripScreenState())
    val screen: StateFlow<TripScreenState> = state.asStateFlow()

    private var loading: Job? = null

    init {
        took?.let { waits.took(it.waitKey) }
        val running = trips.running.value
        val ended = trips.ended.value
        when {
            took == null -> state.update { it.copy(started = true) }
            // Back from the notification, or turned: the trip already runs, or ran
            running?.plan?.took == took || ended?.trip?.plan?.took == took -> state.update { it.copy(started = true) }
            else -> load()
        }
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

    /** What the trip needs and does not have, after asking for it; null when all is there */
    private suspend fun missing(): TripMessage? {
        if (!notifications.granted() && !notifications.request()) return TripMessage.NEEDS_NOTIFICATIONS
        if (!notifications.enabled()) return TripMessage.NEEDS_NOTIFICATIONS
        if (!location.isServiceEnabled()) return TripMessage.LOCATION_OFF
        var permission = location.checkPermission()
        if (permission != LocationPermission.GRANTED) {
            permission = try {
                location.requestPermission()
            } catch (_: IllegalStateException) {
                // No activity to ask from
                LocationPermission.DENIED
            }
        }
        return if (permission == LocationPermission.GRANTED) null else TripMessage.NEEDS_LOCATION
    }

    /** Get off at [alight] instead, while the trip runs */
    fun alightAt(alight: Int?) = trips.alightAt(alight)

    fun finish() = trips.finish()

    fun dismissMessage() = state.update { it.copy(message = null) }

    /** The notification settings, for the message that asks for them */
    fun openNotificationSettings() {
        dismissMessage()
        notifications.openSettings()
    }

    companion object {
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
