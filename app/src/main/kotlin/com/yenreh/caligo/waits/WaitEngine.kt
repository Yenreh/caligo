package com.yenreh.caligo.waits

import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.WaitStep
import com.yenreh.caligo.ui.stops.StopsViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the waits' answers come from */
interface WaitSource {
    /** Where to ask about [bus]'s stop */
    suspend fun pointFor(bus: AwaitedBus): AskPoint

    /**
     * The newest answer already fetched that covers [point], as one a
     * screen following the same stop asked for, if within [maxAge]
     * milliseconds; asks nothing
     */
    fun recent(point: AskPoint, maxAge: Long): WaitAnswer?

    /** Ask the arrivals service around [point] */
    suspend fun ask(point: AskPoint): WaitAnswer
}

/** What the engine asks of the one that shows the waits: the service */
interface WaitHost {
    /** Whether the app's notifications, and its channels for waits, are on */
    fun allowed(): Boolean

    /** [waits] as they read at [now]; the next post comes within [nextPostIn] milliseconds */
    fun show(waits: List<Wait>, now: Long, nextPostIn: Long)

    /** [wait] ended as [ending]; its notification goes */
    fun ended(wait: Wait, ending: WaitEnding)

    /** No wait is left: nothing may run any longer */
    fun idle()

    /** The waits changed: those running now */
    fun changed(waits: List<Wait>) {}
}

/** How starting a wait went */
enum class WaitStart { STARTED, ALREADY_WAITING, CAP_REACHED }

/**
 * The waits and their rounds, on [scope]'s single thread.
 *
 * Each round asks once around each point some waits share, at the pace
 * the home sets by how soon their buses come; an answer a screen fetched
 * for the same stop meanwhile serves instead, and moves the next round
 * on. In between, a beat of [BEAT] reads the waits again, ends those due
 * and shows the rest. Waits that only say how they ended ask nothing,
 * and once the last one ends nothing runs.
 */
class WaitEngine(
    private val source: WaitSource,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val host: WaitHost,
) {
    var waits: List<Wait> = emptyList()
        private set(value) {
            field = value
            host.changed(value)
        }

    private var loop: Job? = null

    /** Requests on their way, by the point asked around */
    private val requests = HashMap<AskPoint, Job>()

    /** Points being looked up, by wait */
    private val resolving = HashMap<WaitKey, Job>()

    /** Something changed: read the waits again now */
    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** Whether nothing runs: no beat, no request, no lookup */
    val isIdle: Boolean get() = loop?.isActive != true && requests.isEmpty() && resolving.isEmpty()

    fun start(bus: AwaitedBus, seed: FadingArrivals? = null): WaitStart {
        if (waits.any { it.key == bus.key }) return WaitStart.ALREADY_WAITING
        if (waits.size >= Wait.CAP) return WaitStart.CAP_REACHED
        waits = waits + Wait.start(bus, now(), seed)
        if (loop?.isActive != true) {
            val beat = scope.launch(start = CoroutineStart.LAZY) { run() }
            loop = beat
            beat.start()
        }
        poke.trySend(Unit)
        return WaitStart.STARTED
    }

    /** End [key]'s wait, if it runs */
    fun end(key: WaitKey, ending: WaitEnding) {
        val wait = waits.firstOrNull { it.key == key } ?: return
        waits = waits - wait
        resolving.remove(key)?.cancel()
        val point = wait.point
        if (point != null && waits.none { it.point == point }) requests.remove(point)?.cancel()
        host.ended(wait, ending)
        if (waits.isEmpty()) stopAll() else poke.trySend(Unit)
    }

    /** End every wait at once */
    fun endAll(ending: WaitEnding) {
        for (wait in waits.toList()) end(wait.key, ending)
    }

    private fun stopAll() {
        for (job in requests.values) job.cancel()
        requests.clear()
        for (job in resolving.values) job.cancel()
        resolving.clear()
        val running = loop
        loop = null
        running?.cancel()
        host.idle()
    }

    private suspend fun run() {
        while (waits.isNotEmpty()) {
            val now = now()
            if (!host.allowed()) {
                endAll(WaitEnding.BLOCKED)
                return
            }
            for (wait in waits.toList()) {
                when (val step = wait.step(now)) {
                    is WaitStep.Ends -> end(wait.key, step.ending)
                    is WaitStep.Goes -> replace(wait.key) { step.wait }
                }
            }
            if (waits.isEmpty()) return
            val wake = rounds(now)
            val gap = (wake - now).coerceIn(MIN_GAP_MS, BEAT_MS)
            host.show(waits, now, gap)
            waits = waits.map { if (it.alertDue(now)) it.copy(alerted = true) else it }
            withTimeoutOrNull(gap) { poke.receive() }
        }
    }

    /** Ask what is due, and say when the next round is */
    private fun rounds(now: Long): Long {
        var wake = now + BEAT_MS
        val asking = waits.filter { it.asks(now) }
        for (wait in asking) {
            if (wait.point != null || wait.key in resolving) continue
            // Kept before it runs: on an immediate dispatcher it may end at once
            val lookup = scope.launch(start = CoroutineStart.LAZY) {
                val point = try {
                    source.pointFor(wait.bus)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AskPoint(wait.bus.anchor.latitude, wait.bus.anchor.longitude, placed = false)
                }
                resolving.remove(wait.key)
                replace(wait.key) { it.copy(point = point) }
                poke.trySend(Unit)
            }
            resolving[wait.key] = lookup
            lookup.start()
        }
        for ((point, group) in asking.filter { it.point != null }.groupBy { it.point!! }) {
            val pace = pace(group, now)
            val answered = group.maxOf { it.answeredAt ?: Long.MIN_VALUE }
            source.recent(point, pace)?.let { if (it.fetchedAt > answered) feed(point, it) }
            if (point in requests) continue
            val last = waits.filter { it.point == point }.maxOf { maxOf(it.answeredAt ?: Long.MIN_VALUE, it.askedAt ?: Long.MIN_VALUE) }
            val due = if (last == Long.MIN_VALUE) now else last + pace
            if (now >= due) ask(point, now) else wake = minOf(wake, due)
        }
        return wake
    }

    private fun ask(point: AskPoint, now: Long) {
        for (wait in waits) if (wait.point == point) replace(wait.key) { it.copy(askedAt = now) }
        val request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val answer = source.ask(point)
                feed(point, answer)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val at = now()
                waits = waits.map { if (it.point == point) it.failed(at) else it }
            } finally {
                if (requests[point] === coroutineContext[Job]) requests.remove(point)
            }
            poke.trySend(Unit)
        }
        requests[point] = request
        request.start()
    }

    private fun feed(point: AskPoint, answer: WaitAnswer) {
        waits = waits.map { if (it.point == point) it.fed(answer) else it }
    }

    private fun replace(key: WaitKey, change: (Wait) -> Wait) {
        waits = waits.map { if (it.key == key) change(it) else it }
    }

    companion object {
        /** How often the waits are read and shown between rounds: the screens' beat */
        val BEAT = 15.seconds

        private val BEAT_MS = BEAT.inWholeMilliseconds
        private const val MIN_GAP_MS = 1_000L

        /** The home's pace, by how soon [group]'s buses come */
        fun pace(group: List<Wait>, now: Long): Long = StopsViewModel.nextRefreshDelay(
            listOf(group.map { it.last.copy(arrivalTime = it.paceTime(now)) }),
            now,
        ).inWholeMilliseconds
    }
}
