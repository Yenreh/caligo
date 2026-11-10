package com.yenreh.caligo.trips

import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripFix
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.TripStep
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.ui.lines.LineViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a trip's buses come from */
fun interface TripSource {
    /** The line's buses running now, as the line screen asks for them */
    suspend fun buses(line: String): List<LineBus>
}

/** What the engine asks of the one that shows the trip: the service */
interface TripHost {
    /** Whether the app's notifications, and its channel for trips, are on */
    fun allowed(): Boolean

    /** [trip] as it reads at [now]; the next post comes within [nextPostIn] milliseconds */
    fun show(trip: Trip, now: Long, nextPostIn: Long)

    /** [trip] ended as [ending]; its notification goes */
    fun ended(trip: Trip, ending: TripEnding)

    /** No trip is left: nothing may run any longer */
    fun idle()

    /** The trip changed: the one running now, if any */
    fun changed(trip: Trip?) {}
}

/**
 * The one trip and what follows it, on [scope]'s single thread: the
 * device's fixes, at the quality the stretch needs, and the line's buses
 * every [BUS_INTERVAL] after each answer, as the line screen follows
 * them. A beat of [BEAT] between them reads the trip again, ends it when
 * due and shows it. Once it ends nothing runs: no fixes asked, no request,
 * no beat.
 */
class TripEngine(
    private val source: TripSource,
    private val location: LocationUpdates,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val host: TripHost,
) {
    var trip: Trip? = null
        private set(value) {
            field = value
            host.changed(value)
        }

    private var loop: Job? = null
    private var request: Job? = null

    /** When the last request for the buses came back, answered or not */
    private var roundAt: Long? = null

    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** Whether nothing runs: no beat, no request, no fixes asked */
    val isIdle: Boolean get() = loop?.isActive != true && request == null && location.requested == null

    /** Follow [plan] from now; a trip running ends first, replaced */
    fun start(plan: TripPlan) {
        trip?.let { finish(it, TripEnding.REPLACED) }
        roundAt = null
        trip = Trip.start(plan, now())
        if (loop?.isActive != true) {
            val beat = scope.launch(start = CoroutineStart.LAZY) { run() }
            loop = beat
            beat.start()
        }
        poke.trySend(Unit)
    }

    /** Get off at [alight] instead, or just follow the ride */
    fun alightAt(alight: Int?) {
        val running = trip ?: return
        trip = running.alightAt(alight, now())
        poke.trySend(Unit)
    }

    /** End the trip, if one runs */
    fun end(ending: TripEnding) {
        val running = trip ?: return
        finish(running, ending)
        stopAll()
    }

    private fun finish(running: Trip, ending: TripEnding) {
        trip = null
        request?.cancel()
        request = null
        location.stop()
        host.ended(running, ending)
    }

    private fun stopAll() {
        val beat = loop
        loop = null
        beat?.cancel()
        host.idle()
    }

    private suspend fun run() {
        while (true) {
            val current = trip ?: return
            val now = now()
            if (!host.allowed()) return end(TripEnding.BLOCKED)
            if (!location.usable()) return end(TripEnding.NO_LOCATION)
            val step = current.step(now)
            if (step is TripStep.Ends) return end(step.ending)
            val near = current.progress(now).stopsLeft <= NEAR_STOPS
            val quality = if (near) FixQuality.HIGH else FixQuality.BALANCED
            if (location.requested != quality) location.request(quality, ::fixed, ::lost)
            val wake = rounds(now)
            val gap = (wake - now).coerceIn(MIN_GAP_MS, BEAT_MS)
            host.show(current, now, gap)
            trip = trip?.warnedAt(now)
            withTimeoutOrNull(gap) { poke.receive() }
        }
    }

    private fun fixed(fix: TripFix) {
        val running = trip ?: return
        trip = running.fixed(fix)
        poke.trySend(Unit)
    }

    private fun lost() {
        poke.trySend(Unit)
    }

    /** Ask for the buses when due, and say when the next round is */
    private fun rounds(now: Long): Long {
        if (request != null) return now + BEAT_MS
        val due = roundAt?.let { it + BUS_INTERVAL_MS } ?: now
        if (now < due) return due
        val line = trip?.plan?.line ?: return now + BEAT_MS
        val asking = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val buses = source.buses(line)
                trip = trip?.withBuses(buses, now())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The guard leaves the service alone meanwhile; the rider's own fixes go on
            } finally {
                if (request === coroutineContext[Job]) {
                    request = null
                    roundAt = now()
                }
            }
            poke.trySend(Unit)
        }
        request = asking
        asking.start()
        return now + BEAT_MS
    }

    companion object {
        /** How often the trip is read and shown between fixes */
        val BEAT = 15.seconds

        /** The line's buses, as often as the line screen asks for them */
        val BUS_INTERVAL = LineViewModel.LINE_BUSES_INTERVAL

        /** Within this many stops of where the ride ends, the fixes are asked at high accuracy */
        const val NEAR_STOPS = 2

        private val BEAT_MS = BEAT.inWholeMilliseconds
        private val BUS_INTERVAL_MS = BUS_INTERVAL.inWholeMilliseconds
        private const val MIN_GAP_MS = 1_000L
    }
}
