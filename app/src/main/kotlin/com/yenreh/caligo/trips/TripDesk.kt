package com.yenreh.caligo.trips

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.waits.Alerts
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the last trip ended; [refused] when the system would not start its service, so it never ran */
data class EndedTrip(val journey: Journey, val ending: TripEnding, val refused: Boolean = false)

/** A trip just finished, which Undo takes back until [until] */
data class FinishingTrip(val journey: Journey, val until: Long)

/** What the screens may do with the trip */
interface TripControl {
    /** The trip running now, or about to; null when none, and while one is finishing */
    val running: StateFlow<Journey?>

    /** The last trip that ended in this run, for the screen that showed it; finished already while finishing */
    val ended: StateFlow<EndedTrip?>

    /** The trip just finished, while Undo can take it back; null with none */
    val finishing: StateFlow<FinishingTrip?>

    /** Follow [plan], the ride "I took it" named, from the screen on show; a trip running is replaced */
    fun start(plan: TripPlan) = start(TripStart.Ride(plan))

    /**
     * Follow the planner's [option] to [destination], from the screen on
     * show, with [lines] the rider chose for some of its rides, by their
     * place among its rides; a trip running is replaced
     */
    fun startJourney(
        option: TripOption,
        destination: String,
        factors: Map<String, Double> = emptyMap(),
        lines: Map<Int, String> = emptyMap(),
    ) = start(TripStart.Planned(option, destination, factors, lines))

    fun start(start: TripStart)

    /** "I took it" on a wait of the rider's own for a line the trip awaits: whether it took that bus as its ride */
    fun took(took: TookRide): Boolean

    /** The buses the trip awaits for the ride ahead, soonest first */
    val awaited: StateFlow<List<AwaitedLine>>

    /** "I took the [line]" on the screen: the ride awaited taken on that line */
    fun takeLine(line: String)

    /** Get off at [alight] instead, or just follow the ride when null */
    fun alightAt(alight: Int?)

    /** The buses at the stops of the planner trip's ride waited for, as the trip heard of them */
    val rideBuses: StateFlow<RideBuses?>

    /** Take the planner trip's ride waited for on [line] alone, or on any of its lines when null */
    fun chooseLine(line: String?)

    /** Finish, on the screen: Undo takes it back for [TripEngine.UNDO_WINDOW]; a start not yet taken ends at once */
    fun finish()

    /** Undo the Finish of the trip [finishing], on the screen: it goes on as before */
    fun undoFinish()

    /** Whether a trip may post its notification: the app's notifications on, and the trips' channel */
    fun allowed(): Boolean

    /** The settings that let a trip post */
    fun openSettings()
}

/**
 * Where the trip screen and the notification's button and swipe reach
 * the trip, one per process, on the main thread. It holds no trip of its
 * own: that lives in [TripService], and a start waits here only until the
 * service takes it. Nothing is stored, so nothing outlives the process.
 *
 * Apart from the waits' desk and service: a trip may run while waits do,
 * and each service carries its own notifications and foreground state.
 * The waits a planner trip starts for its rides run in the trip service,
 * on a wait engine of its own; the waits' desk only shows them on the
 * bells, and ends one tapped there through the trip service.
 */
class TripDesk(
    private val context: Context,
    /** Where the line's buses come from */
    var source: TripSource,
    /** The device's fixes */
    var location: LocationUpdates,
    /** Where what the rides teach is kept, past the trip's end: the app's scope, or a test's */
    var learning: CoroutineScope,
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
    private var serviceFinishingUntil: Long? = null

    private val state = MutableStateFlow<Journey?>(null)
    override val running: StateFlow<Journey?> = state.asStateFlow()

    private val last = MutableStateFlow<EndedTrip?>(null)
    override val ended: StateFlow<EndedTrip?> = last.asStateFlow()

    private val heardBuses = MutableStateFlow<RideBuses?>(null)
    override val rideBuses: StateFlow<RideBuses?> = heardBuses.asStateFlow()

    override fun chooseLine(line: String?) {
        service?.chooseLine(line)
    }

    internal fun rideBusesChanged(buses: RideBuses?) {
        heardBuses.value = buses
    }

    private val finishingTrip = MutableStateFlow<FinishingTrip?>(null)
    override val finishing: StateFlow<FinishingTrip?> = finishingTrip.asStateFlow()

    private val awaitedLines = MutableStateFlow<List<AwaitedLine>>(emptyList())
    override val awaited: StateFlow<List<AwaitedLine>> = awaitedLines.asStateFlow()

    override fun takeLine(line: String) {
        service?.takeLine(line)
    }

    internal fun awaitedChanged(lines: List<AwaitedLine>) {
        awaitedLines.value = lines
    }

    override fun start(start: TripStart) {
        TripNotifications.createChannels(context)
        // The last word of the trip before goes as the next one starts
        NotificationManagerCompat.from(context).cancel(TripNotifications.ENDED_ID)
        last.value = null
        service?.let { return it.start(start) }
        val journey = start.journey(now())
        pending = start
        pendingJourney = journey
        publish()
        // From the screen on show, the app visible: the while-in-use
        // location permission covers the service from here on. Yet the
        // start comes after a permission prompt, which may have left the
        // app in the background: then the system refuses, and the screen
        // hears why.
        try {
            ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
        } catch (_: IllegalStateException) {
            pending = null
            pendingJourney = null
            publish()
            last.value = EndedTrip(journey, TripEnding.BLOCKED, refused = true)
        }
    }

    override fun took(took: TookRide): Boolean = service?.took(took) == true

    override fun allowed(): Boolean = TripNotifications.allowed(context)

    override fun openSettings() {
        context.startActivity(TripNotifications.settingsIntent(context))
    }

    override fun alightAt(alight: Int?) {
        service?.alightAt(alight)
    }

    override fun finish() {
        val waiting = pendingJourney
        if (pending != null) {
            // Never taken by the service: nothing ran, so nothing to undo
            pending = null
            pendingJourney = null
            publish()
            waiting?.let { last.value = EndedTrip(it, TripEnding.FINISHED) }
            return
        }
        service?.finish()
    }

    override fun undoFinish() {
        service?.undo()
    }

    /** "A47: I took it" on the notification of the trip started at [startedAt] */
    fun takeLine(startedAt: Long, line: String) {
        val running = serviceJourney ?: return
        if (running.startedAt == startedAt) service?.takeLine(line)
    }

    /** The notification's Finish, which can be undone, or its swipe, for the trip started at [startedAt] */
    fun act(startedAt: Long, ending: TripEnding) {
        val running = serviceJourney ?: return
        if (running.startedAt != startedAt) return
        if (ending == TripEnding.FINISHED) service?.finish() else service?.end(ending)
    }

    /** The notification's Undo, for the trip started at [startedAt] */
    fun undo(startedAt: Long) {
        val running = serviceJourney ?: return
        if (running.startedAt == startedAt) service?.undo()
    }

    internal fun attach(service: TripService) {
        this.service = service
    }

    internal fun detach(service: TripService) {
        if (this.service !== service) return
        this.service = null
        serviceJourney = null
        serviceFinishingUntil = null
        publish()
    }

    /** The start waiting for the service, handed over once */
    internal fun takePending(): TripStart? = pending.also {
        pending = null
        pendingJourney = null
    }

    internal fun changed(journey: Journey?, finishingUntil: Long?) {
        val undone = finishingTrip.value != null && journey != null && finishingUntil == null
        serviceJourney = journey
        serviceFinishingUntil = finishingUntil
        publish()
        // Taken back: it runs again, not ended
        if (undone) last.value = null
    }

    internal fun endedAs(journey: Journey, ending: TripEnding) {
        last.value = EndedTrip(journey, ending)
    }

    /** Finishing, the trip shows as finished, which Undo takes back; else as it runs */
    private fun publish() {
        val journey = serviceJourney
        val until = serviceFinishingUntil
        if (journey != null && until != null) {
            last.value = EndedTrip(journey, TripEnding.FINISHED)
            state.value = null
            finishingTrip.value = FinishingTrip(journey, until)
        } else {
            finishingTrip.value = null
            state.value = journey ?: pendingJourney
        }
    }
}
