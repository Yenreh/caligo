package com.yenreh.caligo.waits

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.RunningWait
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.waitFor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screens may do with waits */
interface WaitControl {
    /** The trips waited for now, or about to be, each with the time its bus last gave */
    val running: StateFlow<List<RunningWait>>

    /** Wait for [bus], seeded with what its row's list knew of the stop */
    fun start(bus: AwaitedBus, seed: FadingArrivals?): WaitStart

    /** Stop waiting for [key]: the filled bell tapped */
    fun stop(key: WaitKey)

    /** [key]'s bus taken: "I took it" opened the trip screen, which ends the wait */
    fun took(key: WaitKey) {}
}

/**
 * Where the screens and the notifications' buttons reach the waits, one
 * per process, on the main thread. It holds no wait of its own: those
 * live in [WaitService], and a start waits here only until the service
 * takes it. Nothing is stored, so nothing outlives the process.
 */
class WaitDesk(
    private val context: Context,
    /** Where the waits' answers come from */
    var source: WaitSource,
    var now: () -> Long = System::currentTimeMillis,
    /** Where the service runs its waits: the main thread, or a test's */
    var coroutineContext: CoroutineContext = Dispatchers.Main.immediate,
    /** The alert settings, as they are now */
    var alerts: () -> Alerts = { Alerts() },
) : WaitControl {
    private val pending = ArrayList<Pair<AwaitedBus, FadingArrivals?>>()
    private var service: WaitService? = null
    private var serviceWaits = emptyList<RunningWait>()

    /** The waits a trip started, which the trip service runs, and how they end from here */
    private var tripWaits = emptyList<RunningWait>()
    private var tripWaitEnder: ((WaitKey, WaitEnding) -> Unit)? = null

    private val state = MutableStateFlow<List<RunningWait>>(emptyList())
    override val running: StateFlow<List<RunningWait>> = state.asStateFlow()

    override fun start(bus: AwaitedBus, seed: FadingArrivals?): WaitStart {
        val waits = state.value
        if (waits.waitFor(bus.stopId, bus.bus) != null) return WaitStart.ALREADY_WAITING
        // The cap counts the rider's own waits; a trip's have a cap of their own
        if (serviceWaits.size + pending.size >= Wait.CAP) return WaitStart.CAP_REACHED
        WaitNotifications.createChannels(context)
        service?.let { return it.add(bus, seed) }
        pending.add(bus to seed)
        publish()
        // From the screen on show, the bell just tapped. A trip's waits run
        // in the trip service instead; the guard is for whatever else asks
        try {
            ContextCompat.startForegroundService(context, Intent(context, WaitService::class.java))
        } catch (_: IllegalStateException) {
            pending.removeAll { it.first.key == bus.key }
            publish()
            return WaitStart.NOT_ALLOWED
        }
        return WaitStart.STARTED
    }

    override fun stop(key: WaitKey) = end(key, WaitEnding.BELL)

    override fun took(key: WaitKey) = end(key, WaitEnding.TOOK)

    /** End [key] as [ending], wherever it runs: a trip's in the trip service */
    private fun end(key: WaitKey, ending: WaitEnding) {
        if (tripWaits.any { it.key == key }) {
            tripWaitEnder?.invoke(key, ending)
            return
        }
        if (pending.removeAll { it.first.key == key }) publish()
        service?.end(key, ending)
    }

    /**
     * The trip service runs waits of its own from now, which the bells show
     * and end through [ender]
     */
    fun attachTripWaits(ender: (WaitKey, WaitEnding) -> Unit) {
        tripWaitEnder = ender
    }

    /** The trip service has gone, and its waits with it */
    fun detachTripWaits(ender: (WaitKey, WaitEnding) -> Unit) {
        if (tripWaitEnder !== ender) return
        tripWaitEnder = null
        tripWaits = emptyList()
        publish()
    }

    /** The waits the trip service runs now */
    fun tripWaitsChanged(waits: List<RunningWait>) {
        tripWaits = waits
        publish()
    }

    /** A button of [key]'s notification, or its swipe */
    fun act(key: WaitKey, ending: WaitEnding) {
        service?.end(key, ending)
    }

    internal fun attach(service: WaitService) {
        this.service = service
    }

    internal fun detach(service: WaitService) {
        if (this.service !== service) return
        this.service = null
        serviceWaits = emptyList()
        publish()
    }

    /** The starts waiting for the service, handed over once */
    internal fun takePending(): List<Pair<AwaitedBus, FadingArrivals?>> {
        val taken = pending.toList()
        pending.clear()
        return taken
    }

    internal fun changed(waits: List<RunningWait>) {
        serviceWaits = waits
        publish()
    }

    private fun publish() {
        state.value = serviceWaits + pending.map { RunningWait(it.first.key, it.first.bus.arrivalTime, now()) } + tripWaits
    }
}
