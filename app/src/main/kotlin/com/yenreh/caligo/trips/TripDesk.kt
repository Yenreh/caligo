package com.yenreh.caligo.trips

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.waits.Alerts
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the last trip ended */
data class EndedTrip(val journey: Journey, val ending: TripEnding)

/** What the screens may do with the trip */
interface TripControl {
    /** The trip running now, or about to; null when none */
    val running: StateFlow<Journey?>

    /** The last trip that ended in this run, for the screen that showed it */
    val ended: StateFlow<EndedTrip?>

    /** Follow [plan], the ride "I took it" named, from the screen on show; a trip running is replaced */
    fun start(plan: TripPlan) = start(TripStart.Ride(plan))

    /** Follow the planner's [option] to [destination], from the screen on show; a trip running is replaced */
    fun startJourney(option: TripOption, destination: String, factors: Map<String, Double> = emptyMap()) =
        start(TripStart.Planned(option, destination, factors))

    fun start(start: TripStart)

    /** "I took it" on a wait the trip running started: whether it took that bus as its ride */
    fun took(took: TookRide): Boolean = false

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
 * The waits a planner trip starts for its rides are ordinary waits, which
 * the trip service starts and ends through the waits' desk.
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
    private var pending: TripStart? = null
    private var pendingJourney: Journey? = null
    private var service: TripService? = null
    private var serviceJourney: Journey? = null

    private val state = MutableStateFlow<Journey?>(null)
    override val running: StateFlow<Journey?> = state.asStateFlow()

    private val last = MutableStateFlow<EndedTrip?>(null)
    override val ended: StateFlow<EndedTrip?> = last.asStateFlow()

    override fun start(start: TripStart) {
        TripNotifications.createChannels(context)
        last.value = null
        service?.let { return it.start(start) }
        pending = start
        pendingJourney = start.journey(now())
        publish()
        // From the screen on show, the app visible: the while-in-use
        // location permission covers the service from here on
        ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
    }

    override fun took(took: TookRide): Boolean = service?.took(took) == true

    override fun alightAt(alight: Int?) {
        service?.alightAt(alight)
    }

    override fun finish() {
        if (pending != null) {
            pending = null
            pendingJourney = null
            publish()
        }
        service?.end(TripEnding.FINISHED)
    }

    /** A button or the swipe of a wait's notification the trip started */
    fun endWait(key: WaitKey, ending: WaitEnding) {
        service?.endWait(key, ending)
    }

    /** The notification's Finish or its swipe, for the trip started at [startedAt] */
    fun act(startedAt: Long, ending: TripEnding) {
        val running = serviceJourney ?: return
        if (running.startedAt == startedAt) service?.end(ending)
    }

    internal fun attach(service: TripService) {
        this.service = service
    }

    internal fun detach(service: TripService) {
        if (this.service !== service) return
        this.service = null
        serviceJourney = null
        publish()
    }

    /** The start waiting for the service, handed over once */
    internal fun takePending(): TripStart? = pending.also {
        pending = null
        pendingJourney = null
    }

    internal fun changed(journey: Journey?) {
        serviceJourney = journey
        publish()
    }

    internal fun endedAs(journey: Journey, ending: TripEnding) {
        last.value = EndedTrip(journey, ending)
    }

    private fun publish() {
        state.value = serviceJourney ?: pendingJourney
    }
}
