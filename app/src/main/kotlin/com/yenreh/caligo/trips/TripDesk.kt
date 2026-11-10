package com.yenreh.caligo.trips

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.waits.Alerts
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the last trip ended, and when */
data class EndedTrip(val trip: Trip, val ending: TripEnding)

/** What the screens may do with the trip */
interface TripControl {
    /** The trip running now, or about to; null when none */
    val running: StateFlow<Trip?>

    /** The last trip that ended in this run, for the screen that showed it */
    val ended: StateFlow<EndedTrip?>

    /** Follow [plan], from the screen on show; a trip running is replaced */
    fun start(plan: TripPlan)

    /** Get off at [alight] instead, or just follow the ride when null */
    fun alightAt(alight: Int?)

    /** Finish, on the screen */
    fun finish()
}

/**
 * Where the trip screen and the notification's button and swipe reach
 * the trip, one per process, on the main thread. It holds no trip of its
 * own: that lives in [TripService], and a start waits here only until the
 * service takes it. Nothing is stored, so nothing outlives the process.
 *
 * Apart from the waits' desk and service: a trip may run while waits do,
 * and each service carries its own notifications and foreground state.
 */
class TripDesk(
    private val context: Context,
    /** Where the line's buses come from */
    var source: TripSource,
    /** The device's fixes */
    var location: LocationUpdates,
    var now: () -> Long = System::currentTimeMillis,
    /** Where the service runs the trip: the main thread, or a test's */
    var coroutineContext: CoroutineContext = Dispatchers.Main.immediate,
    /** The alert settings, as they are now: they cover the warning too */
    var alerts: () -> Alerts = { Alerts() },
) : TripControl {
    private var pending: TripPlan? = null
    private var service: TripService? = null
    private var serviceTrip: Trip? = null

    private val state = MutableStateFlow<Trip?>(null)
    override val running: StateFlow<Trip?> = state.asStateFlow()

    private val last = MutableStateFlow<EndedTrip?>(null)
    override val ended: StateFlow<EndedTrip?> = last.asStateFlow()

    override fun start(plan: TripPlan) {
        TripNotifications.createChannels(context)
        last.value = null
        service?.let { return it.start(plan) }
        pending = plan
        publish()
        // From the screen on show, the app visible: the while-in-use
        // location permission covers the service from here on
        ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
    }

    override fun alightAt(alight: Int?) {
        pending?.let {
            pending = it.copy(alight = alight)
            publish()
        }
        service?.alightAt(alight)
    }

    override fun finish() {
        if (pending != null) {
            pending = null
            publish()
        }
        service?.end(TripEnding.FINISHED)
    }

    /** The notification's Finish or its swipe, for the trip started at [startedAt] */
    fun act(startedAt: Long, ending: TripEnding) {
        val running = serviceTrip ?: return
        if (running.startedAt == startedAt) service?.end(ending)
    }

    internal fun attach(service: TripService) {
        this.service = service
    }

    internal fun detach(service: TripService) {
        if (this.service !== service) return
        this.service = null
        serviceTrip = null
        publish()
    }

    /** The start waiting for the service, handed over once */
    internal fun takePending(): TripPlan? = pending.also { pending = null }

    internal fun changed(trip: Trip?) {
        serviceTrip = trip
        publish()
    }

    internal fun endedAs(trip: Trip, ending: TripEnding) {
        last.value = EndedTrip(trip, ending)
    }

    private fun publish() {
        state.value = serviceTrip ?: pending?.let { Trip.start(it, now()) }
    }
}
