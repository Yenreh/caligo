package com.yenreh.caligo.trips

import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.JourneyStep
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripFix
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.metersBetween
import com.yenreh.caligo.ui.lines.LineViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a trip's buses come from, and where what it learns goes */
interface TripSource {
    /** The line's buses running now, as the line screen asks for them */
    suspend fun buses(line: String): List<LineBus>

    /** Buses on their way to [stops], as the planner asks for them, reusing what it asked lately */
    suspend fun arrivals(stops: List<LineStop>): LiveArrivals

    /** Teach the planner how the rides just followed ran, by line */
    suspend fun observeRides(readings: Map<String, Double>)
}

/** How a trip starts: one ride taken, or a whole option from the planner */
sealed interface TripStart {
    fun journey(now: Long): Journey

    data class Ride(val plan: TripPlan) : TripStart {
        override fun journey(now: Long) = Journey.single(plan, now)
    }

    data class Planned(val option: TripOption, val destination: String, val factors: Map<String, Double> = emptyMap()) : TripStart {
        override fun journey(now: Long) = Journey.planned(option, destination, now, factors)
    }
}

/** What the engine asks of the one that shows the trip: the service */
interface TripHost {
    /** Whether the app's notifications, and its channel for trips, are on */
    fun allowed(): Boolean

    /** [journey] as it reads at [now]; the next post comes within [nextPostIn] milliseconds */
    fun show(journey: Journey, now: Long, nextPostIn: Long)

    /** [journey] ended as [ending]; its notification goes */
    fun ended(journey: Journey, ending: TripEnding)

    /** No trip is left: nothing may run any longer */
    fun idle()

    /** The trip changed: the one running now, if any */
    fun changed(journey: Journey?)

    /** Wait for each of [buses], as far as the cap of a trip's waits allows: the keys of those started */
    fun startWaits(buses: List<AwaitedBus>): List<WaitKey>

    /** Stop the waits [keys], without asking */
    fun stopWaits(keys: List<WaitKey>)

    /** The waits running now */
    fun runningWaits(): Set<WaitKey>

    /** When the soonest bus the trip awaits is due, as its wait reads it at [now]; null with none */
    fun soonestAwaited(now: Long): Long?
}

/**
 * The one trip and what follows it, on [scope]'s single thread: the
 * device's fixes, at the quality the stretch needs; the buses of the line
 * ridden, or of the lines that make the ride waited for once the rider
 * nears its stop, every [BUS_INTERVAL] after each answer, as the line
 * screen follows them; and the waits for the next ride's buses, started
 * as the leg to it begins, asked for again when none is left, and ended
 * once a bus is taken. A beat of [BEAT] between them reads the trip
 * again, moves it from leg to leg, ends it when due and shows it. Once it
 * ends nothing runs: no fixes asked, no request, no wait of its own, no
 * beat.
 */
class TripEngine(
    private val source: TripSource,
    private val location: LocationUpdates,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val host: TripHost,
    /** Where what a ride teaches is kept: outlives the trip, so the end of one cuts no reading short */
    private val learning: CoroutineScope,
) {
    var journey: Journey? = null
        private set(value) {
            field = value
            host.changed(value)
        }

    private var loop: Job? = null
    private var request: Job? = null
    private var asking: Job? = null

    /** When the last request for the buses came back, answered or not */
    private var roundAt: Long? = null

    /** The last round found a bus of the ride waited for nearing its stop */
    private var busNear = false

    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** Whether nothing runs: no beat, no request, no fixes asked */
    val isIdle: Boolean get() = loop?.isActive != true && request == null && asking == null && location.requested == null

    /** Follow what [start] says from now; a trip running ends first, replaced */
    fun start(start: TripStart) {
        journey?.let { finish(it, TripEnding.REPLACED) }
        roundAt = null
        busNear = false
        journey = start.journey(now())
        if (loop?.isActive != true) {
            val beat = scope.launch(start = CoroutineStart.LAZY) { run() }
            loop = beat
            beat.start()
        }
        poke.trySend(Unit)
    }

    /** Get off at [alight] instead, or just follow the ride */
    fun alightAt(alight: Int?) {
        val running = journey ?: return
        val ride = running.ride ?: return
        journey = running.copy(ride = ride.alightAt(alight, now()))
        poke.trySend(Unit)
    }

    /** "I took it" on a wait for [took]'s line at the ride waited for; whether the trip took it */
    fun took(took: TookRide): Boolean = take(journey?.tookIt(took, now()))

    /** "I took the [line]" on the notification: the ride awaited taken on that line, every wait of it ending */
    fun takeLine(line: String) {
        take(journey?.tookLine(line, now()))
    }

    /** The trip goes on as [taken], a bus taken, if any: every wait of the ride goes, the one taken too */
    private fun take(taken: Journey?): Boolean {
        journey = taken ?: return false
        settleWaits()
        poke.trySend(Unit)
        return true
    }

    /** The alert of the awaited bus near has gone out, for the ride awaited */
    fun busAlerted() {
        val running = journey ?: return
        journey = running.copy(busAlertedFor = running.pendingRide)
    }

    /** What the trip shows changed, as its awaited buses do: show it again now */
    fun refresh() {
        poke.trySend(Unit)
    }

    /** The rider stopped a wait of the ride waited for themselves: its waits are not asked for again */
    fun waitDeclined() {
        val running = journey ?: return
        journey = running.copy(declinedFor = running.pendingRide)
    }

    /** End the trip, if one runs */
    fun end(ending: TripEnding) {
        val running = journey ?: return
        finish(running, ending)
        stopAll()
    }

    private fun finish(running: Journey, ending: TripEnding) {
        journey = null
        request?.cancel()
        request = null
        asking?.cancel()
        asking = null
        location.stop()
        // The waits it started go with it
        if (running.awaited.isNotEmpty()) host.stopWaits(running.awaited)
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
            val current = journey ?: return
            val now = now()
            if (!host.allowed()) return end(TripEnding.BLOCKED)
            if (!location.usable()) return end(TripEnding.NO_LOCATION)
            when (val step = current.step(now)) {
                is JourneyStep.Ends -> {
                    learn(step.readings)
                    return end(step.ending)
                }
                is JourneyStep.Goes -> {
                    learn(step.readings)
                    if (step.journey != current) {
                        journey = step.journey
                        roundAt = null
                    }
                }
            }
            settleWaits()
            awaitRide(checkNotNull(journey), now)
            val running = checkNotNull(journey)
            val quality = quality(running, now)
            if (location.requested != quality) location.request(quality, ::fixed, ::lost)
            val wake = rounds(now)
            val gap = (wake - now).coerceIn(MIN_GAP_MS, BEAT_MS)
            host.show(checkNotNull(journey), now, gap)
            journey = journey?.let { j -> j.ride?.let { j.copy(ride = it.warnedAt(now)) } ?: j }
            withTimeoutOrNull(gap) { poke.receive() }
        }
    }

    /**
     * High accuracy near the stop to get off at, and near the stop to
     * board at; waiting there, only once a bus to take nears it, so its
     * fixes tell the rider leaving on it; balanced elsewhere
     */
    private fun quality(journey: Journey, now: Long): FixQuality {
        val near = when (val phase = journey.phase(now)) {
            is JourneyPhase.Riding -> phase.trip.progress(now).stopsLeft <= NEAR_STOPS
            is JourneyPhase.Waiting -> busNear || host.soonestAwaited(now)?.let { it - now <= AWAITED_NEAR_MS } == true
            is JourneyPhase.Walking -> journey.nearBoarding(now)
        }
        return if (near) FixQuality.HIGH else FixQuality.BALANCED
    }

    private fun fixed(fix: TripFix) {
        val running = journey ?: return
        journey = running.fixed(fix)
        poke.trySend(Unit)
    }

    private fun lost() {
        poke.trySend(Unit)
    }

    private fun learn(readings: Map<String, Double>) {
        if (readings.isEmpty()) return
        learning.launch {
            try {
                source.observeRides(readings)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Learning is a bonus: the trip goes on all the same
            }
        }
    }

    /** Once a bus is taken the waits for its ride end, the one taken too, and so do those of a ride left behind */
    private fun settleWaits() {
        val running = journey ?: return
        if (running.awaited.isEmpty() || running.pendingRide == running.awaitedFor) return
        host.stopWaits(running.awaited)
        journey = running.copy(awaited = emptyList(), awaitedFor = null, awaitedAt = null)
    }

    /**
     * The waits for the ride waited for: started as the leg to it begins,
     * and asked for again when all have ended with none taken
     */
    private fun awaitRide(running: Journey, now: Long) {
        if (!running.planned || asking != null) return
        val pending = running.pendingRide ?: return
        if (running.declinedFor == pending) return
        if (running.awaitedFor == pending) {
            if (running.awaited.any { it in host.runningWaits() }) return
            if (now - (running.awaitedAt ?: now) < AWAIT_AGAIN_MS) return
        }
        val candidates = running.pendingCandidates
        // From when the rider gets to the stop
        val walk = (running.phase(now) as? JourneyPhase.Walking)?.millis ?: 0
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var keys: List<WaitKey> = emptyList()
            try {
                val live = source.arrivals(candidates.flatMap { listOf(it.board, it.alight) })
                keys = host.startWaits(awaitedBuses(live, candidates, now + walk))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Asked again a little later
            } finally {
                if (asking === coroutineContext[Job]) asking = null
            }
            val current = journey
            // A ride no longer waited for: not its waits
            if (current == null || current.pendingRide != pending) {
                host.stopWaits(keys)
                return@launch
            }
            journey = current.awaiting(pending, keys, now())
            poke.trySend(Unit)
        }
        asking = job
        job.start()
    }

    /** Ask for the buses when due, and say when the next round is */
    private fun rounds(now: Long): Long {
        if (request != null) return now + BEAT_MS
        val due = roundAt?.let { it + BUS_INTERVAL_MS } ?: now
        if (now < due) return due
        val lines = linesToAsk(journey ?: return now + BEAT_MS, now)
        if (lines.isEmpty()) {
            busNear = false
            return now + BEAT_MS
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var near = false
                for (line in lines) {
                    try {
                        val buses = source.buses(line)
                        near = near || journey?.busNear(line, buses, BUS_NEAR_METERS) == true
                        journey = journey?.withBuses(line, buses, now())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // The guard leaves the service alone meanwhile; the rider's own fixes go on
                    }
                }
                busNear = near
            } finally {
                if (request === coroutineContext[Job]) {
                    request = null
                    roundAt = now()
                }
            }
            settleWaits()
            poke.trySend(Unit)
        }
        request = job
        job.start()
        return now + BEAT_MS
    }

    /**
     * The lines whose buses to follow: the one ridden; for a ride waited
     * for, each of its lines once the rider is near its stop, to tell
     * which one they leave on. Waiting, not once a fresh fix has them
     * gone elsewhere, away from the stop and its lines' routes.
     */
    private fun linesToAsk(running: Journey, now: Long): List<String> {
        running.ride?.let { return listOf(it.plan.line) }
        val candidates = running.pendingCandidates
        val fix = running.fix
        if (running.phase(now) is JourneyPhase.Waiting) {
            val fresh = fix?.takeIf { now - it.at <= FIX_FRESH_MS }
            return if (fresh == null || running.nearRide(fresh.point, Journey.NEAR_BOARD_METERS)) candidates.map { it.line } else emptyList()
        }
        val near = fix != null && candidates.any {
            metersBetween(fix.point.latitude, fix.point.longitude, it.board.latitude, it.board.longitude) <= Journey.NEAR_BOARD_METERS
        }
        return if (near) candidates.map { it.line } else emptyList()
    }

    companion object {
        /** How often the trip is read and shown between fixes */
        val BEAT = 15.seconds

        /** The line's buses, as often as the line screen asks for them */
        val BUS_INTERVAL = LineViewModel.LINE_BUSES_INTERVAL

        /** Within this many stops of where the ride ends, the fixes are asked at high accuracy */
        const val NEAR_STOPS = 2

        /** The waits for a ride, all ended with none taken, are asked for again after this */
        val AWAIT_AGAIN = 60.seconds

        /** Waiting at the stop, a bus to take within this of it asks the fixes at high accuracy */
        const val BUS_NEAR_METERS = 600.0

        /** Waiting at the stop, an awaited bus due within this asks the fixes at high accuracy */
        val AWAITED_NEAR = 2.minutes

        private val BEAT_MS = BEAT.inWholeMilliseconds
        private val BUS_INTERVAL_MS = BUS_INTERVAL.inWholeMilliseconds
        private val AWAIT_AGAIN_MS = AWAIT_AGAIN.inWholeMilliseconds
        private val AWAITED_NEAR_MS = AWAITED_NEAR.inWholeMilliseconds
        private val FIX_FRESH_MS = Trip.FIX_FRESH.inWholeMilliseconds
        private const val MIN_GAP_MS = 1_000L

        /**
         * The next bus of each of [candidates], the lines making one ride,
         * that the rider can still catch at its stop from [from]: as a wait
         * follows it, at the stop the answers list it at
         */
        fun awaitedBuses(live: LiveArrivals, candidates: List<RideLeg>, from: Long): List<AwaitedBus> =
            candidates.mapNotNull { ride ->
                val next = live.nextBus(ride.line, ride.board, ride.alight, from) ?: return@mapNotNull null
                val stop = live.stops.firstOrNull { s -> next.bus in s.arrivals } ?: return@mapNotNull null
                val anchor = GeoPoint(ride.board.latitude, ride.board.longitude)
                val lat = stop.latitude
                val lon = stop.longitude
                AwaitedBus(
                    stopId = stop.id,
                    stopName = stop.name,
                    bus = next.bus,
                    anchor = anchor,
                    place = if (lat != null && lon != null) GeoPoint(lat, lon) else anchor.takeIf { stop.id == ride.board.stopId },
                )
            }
    }
}
