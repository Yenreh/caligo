package com.yenreh.caligo.waits

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitKey
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screens may do with waits */
interface WaitControl {
    /** The buses waited for now, or about to be */
    val running: StateFlow<Set<WaitKey>>

    /** Wait for [bus], seeded with what its row's list knew of the stop */
    fun start(bus: AwaitedBus, seed: FadingArrivals?): WaitStart

    /** Stop waiting for [key]: the filled bell tapped */
    fun stop(key: WaitKey)
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
    private var serviceKeys = emptySet<WaitKey>()

    private val state = MutableStateFlow<Set<WaitKey>>(emptySet())
    override val running: StateFlow<Set<WaitKey>> = state.asStateFlow()

    override fun start(bus: AwaitedBus, seed: FadingArrivals?): WaitStart {
        val keys = state.value
        if (bus.key in keys) return WaitStart.ALREADY_WAITING
        if (keys.size >= Wait.CAP) return WaitStart.CAP_REACHED
        WaitNotifications.createChannels(context)
        service?.let { return it.add(bus, seed) }
        pending.add(bus to seed)
        publish()
        // From the screen on show, the bell just tapped
        ContextCompat.startForegroundService(context, Intent(context, WaitService::class.java))
        return WaitStart.STARTED
    }

    override fun stop(key: WaitKey) {
        if (pending.removeAll { it.first.key == key }) publish()
        service?.end(key, WaitEnding.BELL)
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
        serviceKeys = emptySet()
        publish()
    }

    /** The starts waiting for the service, handed over once */
    internal fun takePending(): List<Pair<AwaitedBus, FadingArrivals?>> {
        val taken = pending.toList()
        pending.clear()
        return taken
    }

    internal fun changed(keys: Set<WaitKey>) {
        serviceKeys = keys
        publish()
    }

    private fun publish() {
        state.value = serviceKeys + pending.map { it.first.key }
    }
}
