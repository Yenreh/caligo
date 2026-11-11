package com.yenreh.caligo.trips

import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.AskPoint
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
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.RideLeg
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.failureBackoff
import com.yenreh.caligo.domain.metersBetween
import com.yenreh.caligo.domain.stationOf
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
    /**
     * The line's buses running now, as the line screen asks for them; the
     * direction of each asked for only near [near], where the trip tells
     * its buses, when given
     */
    suspend fun buses(line: String, near: GeoPoint?): List<LineBus>

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

    /** [lines]: the line the rider chose for some of its rides, by their place among its rides */
    data class Planned(
        val option: TripOption,
        val destination: String,
        val factors: Map<String, Double> = emptyMap(),
        val lines: Map<Int, String> = emptyMap(),
    ) : TripStart {
        override fun journey(now: Long) = Journey.planned(option, destination, now, factors, lines)
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

    /** The trip changed: the one running now, if any, and when its Finish takes effect while it can be undone */
    fun changed(journey: Journey?, finishingUntil: Long?)

    /** Wait for each of [buses], as far as the cap of a trip's waits allows: the keys of those started */
    fun startWaits(buses: List<AwaitedBus>): List<WaitKey>

    /** Stop the waits [keys], without asking */
    fun stopWaits(keys: List<WaitKey>)

    /** The waits running now */
    fun runningWaits(): Set<WaitKey>

    /** The line the wait [key] follows; null when it no longer runs */
    fun awaitedLine(key: WaitKey): String?

    /** The buses at the stops of the ride waited for changed, as heard; null with no ride waited for */
    fun rideBusesChanged(buses: RideBuses?)

    /** When the soonest bus the trip awaits is due, as its wait reads it at [now]; null with none */
    fun soonestAwaited(now: Long): Long?

    /** The buses the trip awaits that reached their stop and passed, while their waits ask: when each line's left */
    fun leftBuses(now: Long): Map<String, Long>
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
            host.changed(value, finishingUntil)
        }

    /**
     * When a Finish takes effect, while it can still be undone; null with
     * none. Meanwhile the trip runs on underneath as it was, its fixes,
     * rounds and waits, so an Undo restarts nothing; it only says nothing:
     * no warning, no bus alert, no last word, and however it would end, it
     * ends as finished.
     */
    var finishingUntil: Long? = null
        private set

    private var loop: Job? = null
    private var request: Job? = null
    private var asking: Job? = null

    /** When the last request for the buses came back, answered or not */
    private var roundAt: Long? = null

    /** Rounds of the buses in a row that no line answered: each puts the next off, as [failureBackoff] says */
    private var roundFailures = 0

    /** The last round found a bus of the ride waited for nearing its stop */
    private var busNear = false

    /**
     * The ride [awaitFor] waited for, the arrivals asked in a row that
     * listed no bus of it to catch, and those that failed: each makes the
     * next ask wait longer, until a bus is found, the leg changes or a bus
     * is taken
     */
    private var awaitFor: Int? = null
    private var awaitEmpty = 0
    private var awaitFailures = 0

    /** The ride waited for is asked about at the next beat, whatever its waits: its lines changed */
    private var askNow = false

    /** The buses at the stops of the ride waited for, as the trip heard of them */
    var rideBuses: RideBuses? = null
        private set(value) {
            if (value == field) return
            field = value
            host.rideBusesChanged(value)
        }

    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** Whether nothing runs: no beat, no request, no fixes asked */
    val isIdle: Boolean get() = loop?.isActive != true && request == null && asking == null && location.requested == null

    /** Follow what [start] says from now; a trip running ends first, replaced, or finished if it was finishing */
    fun start(start: TripStart) {
        journey?.let { close(it, if (finishingUntil != null) TripEnding.FINISHED else TripEnding.REPLACED) }
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
        if (finishingUntil != null) return
        val running = journey ?: return
        val ride = running.ride ?: return
        journey = running.copy(ride = ride.alightAt(alight, now()))
        poke.trySend(Unit)
    }

    /** "I took it" on a wait for [took]'s line at the ride waited for; whether the trip took it: never one finishing */
    fun took(took: TookRide): Boolean = finishingUntil == null && take(journey?.tookIt(took, now()))

    /**
     * "I took the [line]" on the screen, "[line]: I took it" or "I took it" on the
     * notification: the ride awaited taken on that line, every wait of it
     * ending. Late, within [LATE_TAKE] of that line's awaited bus leaving
     * the stop, the ride starts from its leaving, where the estimate then
     * has the bus by now.
     */
    fun takeLine(line: String) {
        if (finishingUntil == null) take(journey?.tookLine(line, boardedAt(line)))
    }

    /** When a ride taken on [line] now began: as its awaited bus left the stop lately, else now */
    private fun boardedAt(line: String): Long {
        val now = now()
        val left = departures.takeIf { departuresFor == journey?.pendingRide }?.get(line) ?: return now
        return if (now - left in 0..LATE_TAKE_MS) left else now
    }

    /**
     * When the bus of each line the trip awaits for ride [departuresFor]
     * last left its stop, as its wait saw it: a ride taken late counts from
     * then, and fixes leaving at a bus's pace soon after take that line
     */
    private val departures = HashMap<String, Long>()
    private var departuresFor: Int? = null

    /** A bus of [line] the trip awaits left its stop at [at], as its wait saw it reach the stop and pass */
    fun busLeft(line: String, at: Long) {
        val pending = journey?.pendingRide ?: return
        if (departuresFor != pending) {
            departures.clear()
            departuresFor = pending
        }
        departures[line] = maxOf(at, departures[line] ?: Long.MIN_VALUE)
    }

    /** The trip goes on as [taken], a bus taken, if any: every wait of the ride goes, the one taken too */
    private fun take(taken: Journey?): Boolean {
        journey = taken ?: return false
        awaitFor = null
        settleWaits()
        poke.trySend(Unit)
        return true
    }

    /**
     * Take the ride waited for on [line] alone, or on any of its lines when
     * null: awaited, alerted for and told as chosen, while the rounds near
     * the stop still follow every line, to tell the bus the rider leaves
     * on whichever it is. Chosen, the
     * waits of the other lines stop, not as declined, and the line is asked
     * for at once when no wait of it runs; any line again, each is awaited
     * again, asked for at once.
     */
    fun chooseLine(line: String?) {
        if (finishingUntil != null) return
        val running = journey ?: return
        if (!running.planned || running.ride != null) return
        val pending = running.pendingRide ?: return
        val next = running.choosing(line)
        if (next.chosenLine == running.chosenLine) return
        var chosen = next
        if (line != null && running.awaitedFor == pending) {
            val (keep, stop) = running.awaited.partition { host.awaitedLine(it) == line }
            host.stopWaits(stop)
            chosen = chosen.copy(awaited = keep)
            if (keep.none { it in host.runningWaits() }) askNow = true
        } else if (line == null) {
            askNow = true
        }
        // What the asks before found was for other lines
        awaitFor = null
        busNear = false
        journey = chosen
        poke.trySend(Unit)
    }

    /** [answer], asked around [point] by a wait of the trip: every bus at the stops of the ride waited for it reaches */
    fun heard(point: AskPoint, answer: WaitAnswer) {
        val running = journey ?: return
        val pending = running.pendingRide ?: return
        rideBuses = heardOf(pending).heard(point, answer, running.pendingCandidates)
    }

    private fun heardOf(pending: Int): RideBuses = rideBuses?.takeIf { it.ride == pending } ?: RideBuses(pending)

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

    /** Finish: the trip ends as finished [UNDO_WINDOW] from now, unless undone; a Finish again changes nothing */
    fun finish() {
        val running = journey ?: return
        if (finishingUntil != null) return
        finishingUntil = now() + UNDO_WINDOW_MS
        host.changed(running, finishingUntil)
        poke.trySend(Unit)
    }

    /** Undo a Finish while it can be: the trip goes on as before, and shows so at once */
    fun undo() {
        val running = journey ?: return
        if (finishingUntil == null) return
        finishingUntil = null
        host.changed(running, null)
        poke.trySend(Unit)
    }

    /** End the trip, if one runs; while finishing, as finished whatever ends it */
    fun end(ending: TripEnding) {
        val running = journey ?: return
        close(running, if (finishingUntil != null) TripEnding.FINISHED else ending)
        stopAll()
    }

    private fun close(running: Journey, ending: TripEnding) {
        finishingUntil = null
        rideBuses = null
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
            val until = finishingUntil
            if (until != null && now >= until) return end(TripEnding.FINISHED)
            if (!host.allowed()) return end(TripEnding.BLOCKED)
            if (!location.usable()) return end(TripEnding.NO_LOCATION)
            // Read at every round: the rider may make it precise, or not, meanwhile
            val approximate = !location.precise()
            if (current.approximate != approximate) journey = current.copy(approximate = approximate)
            when (val step = checkNotNull(journey).step(now)) {
                is JourneyStep.Ends -> {
                    learn(step.readings)
                    return end(step.ending)
                }
                is JourneyStep.Goes -> {
                    learn(step.readings)
                    if (step.journey != journey) {
                        journey = step.journey
                        roundAt = null
                    }
                }
            }
            settleWaits()
            for ((line, left) in host.leftBuses(now)) busLeft(line, left)
            // Another ride waited for, or none: what was heard of the last goes
            if (rideBuses?.ride != journey?.pendingRide) rideBuses = null
            awaitRide(checkNotNull(journey), now)
            val running = checkNotNull(journey)
            val quality = quality(running, now)
            if (location.requested != quality) location.request(quality, ::fixed, ::lost)
            val wake = rounds(now)
            // Finishing, the beat wakes as the Finish takes effect
            val gap = (wake - now).coerceIn(MIN_GAP_MS, BEAT_MS).let { g -> until?.let { minOf(g, (it - now).coerceAtLeast(1)) } ?: g }
            host.show(checkNotNull(journey), now, gap)
            // Finishing, no warning goes out: an Undo still gives it
            if (until == null) journey = journey?.let { j -> j.ride?.let { j.copy(ride = it.warnedAt(now)) } ?: j }
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

    /** A fix: read the trip again only when it moved it, not for one too rough to count */
    /**
     * A fix: read the trip again only when it moved it, not for one too
     * rough to count. Leaving the stop at a bus's pace along a line soon
     * after its awaited bus left, the rider took that bus, whether or not
     * a round placed it.
     */
    private fun fixed(fix: TripFix) {
        val running = journey ?: return
        val moved = running.fixed(fix)
        if (moved == running) return
        journey = moved
        if (finishingUntil == null && departuresFor == moved.pendingRide) {
            val left = departures.entries.firstOrNull { (line, at) -> fix.at - at in 0..LEFT_ON_WITHIN_MS && moved.leavingOn(line, fix.at) != null }
            if (left != null) {
                take(moved.tookLine(left.key, left.value))
                return
            }
        }
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
     * and asked for again when all have ended with none taken, after
     * [AWAIT_AGAIN], then 2 and 4 minutes while no bus of it to catch is
     * listed, and longer after failures, as [failureBackoff] says
     */
    private fun awaitRide(running: Journey, now: Long) {
        if (!running.planned || asking != null) return
        val pending = running.pendingRide ?: return
        if (running.declinedFor == pending) return
        // Another leg: what the asks before found says nothing of it
        if (awaitFor != pending) {
            awaitFor = pending
            awaitEmpty = 0
            awaitFailures = 0
        }
        if (running.awaitedFor == pending && !askNow) {
            if (running.awaited.any { it in host.runningWaits() }) return
            if (now - (running.awaitedAt ?: now) < awaitAgain()) return
        }
        askNow = false
        val candidates = running.awaitedCandidates
        // A line whose wait runs already, as the one chosen before Any, keeps it
        val waited = if (running.awaitedFor == pending) {
            running.awaited.filter { it in host.runningWaits() }.mapNotNullTo(HashSet()) { host.awaitedLine(it) }
        } else {
            emptySet()
        }
        // From when the rider gets to the stop
        val walk = (running.phase(now) as? JourneyPhase.Walking)?.millis ?: 0
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var keys: List<WaitKey> = emptyList()
            var found: Boolean? = null
            try {
                val live = source.arrivals(stopsToAsk(candidates))
                val buses = awaitedBuses(live, candidates, now + walk)
                // What it lists of every line, the one chosen or not, for the screen, the buses awaited among them
                journey?.takeIf { it.pendingRide == pending }?.let { heardOf(pending).awaiting(buses, it.pendingCandidates, live) }?.let { rideBuses = it }
                // None, as an area that could not be asked leaves it: a failure, not an answer
                found = if (buses.isEmpty() && live.failed) null else buses.isNotEmpty()
                keys = host.startWaits(buses.filter { it.bus.line !in waited })
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
            // A line chosen meanwhile: the others' waits go
            val lines = current.pendingLines
            val (wanted, unwanted) = keys.partition { host.awaitedLine(it) in lines }
            host.stopWaits(unwanted)
            // The waits already running for the ride stay, as for the line chosen before Any
            val kept = if (current.awaitedFor == pending) current.awaited.filter { it in host.runningWaits() } else emptyList()
            keys = (kept + wanted).distinct()
            if (awaitFor == pending) {
                when (found) {
                    null -> awaitFailures++
                    true -> {
                        awaitEmpty = 0
                        awaitFailures = 0
                    }
                    false -> {
                        awaitEmpty++
                        awaitFailures = 0
                    }
                }
            }
            journey = current.awaiting(pending, keys, now())
            poke.trySend(Unit)
        }
        asking = job
        job.start()
    }

    /** How long after the last ask the ride waited for is asked about again */
    private fun awaitAgain(): Long {
        val empty = if (awaitEmpty <= 1) 1L else 1L shl minOf(awaitEmpty - 1, 2)
        return AWAIT_AGAIN_MS * empty + failureBackoff(awaitFailures).inWholeMilliseconds
    }

    /**
     * The stops whose arrivals tell the next bus of each of [candidates]:
     * where it boards; where it gets off only for a station's platform,
     * since a bus listed at another platform of it counts only once seen
     * there, as [LiveArrivals.nextBus] tells
     */
    private fun stopsToAsk(candidates: List<RideLeg>): List<LineStop> = candidates
        .flatMap { if (stationOf(it.board.name) != null) listOf(it.board, it.alight) else listOf(it.board) }
        .distinctBy { it.stopId }

    /** Ask for the buses when due, after failures later, and say when the next round is */
    private fun rounds(now: Long): Long {
        if (request != null) return now + BEAT_MS
        val due = roundAt?.let { it + BUS_INTERVAL_MS + failureBackoff(roundFailures).inWholeMilliseconds } ?: now
        if (now < due) return due
        val lines = linesToAsk(journey ?: return now + BEAT_MS, now)
        if (lines.isEmpty()) {
            busNear = false
            return now + BEAT_MS
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var near = false
                var answered = false
                for (line in lines) {
                    try {
                        val buses = source.buses(line, journey?.let { nearOf(it, line, now) })
                        answered = true
                        near = near || journey?.busNear(line, buses, BUS_NEAR_METERS) == true
                        journey = journey?.withBuses(line, buses, now())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // The guard leaves the service alone meanwhile; the rider's own fixes go on
                    }
                }
                busNear = near
                roundFailures = if (answered) 0 else roundFailures + 1
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
     * Where the trip tells [line]'s buses at [now]: the rider's fresh good
     * fix; on the bus without one, where the estimate has them on its
     * route; waiting for a ride, its stop to board on that line
     */
    private fun nearOf(running: Journey, line: String, now: Long): GeoPoint? {
        running.fix?.takeIf { now - it.at <= FIX_FRESH_MS }?.let { return it.point }
        running.ride?.let { return it.route.pointAt(it.progress(now).along) }
        val board = running.pendingCandidates.firstOrNull { it.line == line }?.board ?: return null
        return GeoPoint(board.latitude, board.longitude)
    }

    /**
     * The lines whose buses to follow: the one ridden; for a ride waited
     * for, each of its lines once the rider is near its stop, to tell
     * which one they leave on. Waiting, not once a fresh fix has them
     * gone elsewhere, away from the stop and its lines' routes. Every line
     * of it, one chosen or not: these rounds tell the bus the rider leaves
     * on, which may be another after all.
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

        /** A ride taken this long at most after its awaited bus left counts from its leaving */
        val LATE_TAKE = 15.minutes

        /** Fixes leaving at a bus's pace this long at most after a line's awaited bus left take that line */
        val LEFT_ON_WITHIN = 5.minutes

        /** How long a Finish can be undone, from the screen, the shade or the lock screen */
        val UNDO_WINDOW = 8.seconds

        /** The line's buses, as often as the line screen asks for them */
        val BUS_INTERVAL = LineViewModel.LINE_BUSES_INTERVAL

        /** Within this many stops of where the ride ends, the fixes are asked at high accuracy */
        const val NEAR_STOPS = 2

        /**
         * The waits for a ride, all ended with none taken, are asked for
         * again after this; while the arrivals list no bus of it to catch,
         * after twice and then four times as long
         */
        val AWAIT_AGAIN = 60.seconds

        /** Waiting at the stop, a bus to take within this of it asks the fixes at high accuracy */
        const val BUS_NEAR_METERS = 600.0

        /** Waiting at the stop, an awaited bus due within this asks the fixes at high accuracy */
        val AWAITED_NEAR = 2.minutes

        private val BEAT_MS = BEAT.inWholeMilliseconds
        private val BUS_INTERVAL_MS = BUS_INTERVAL.inWholeMilliseconds
        private val AWAIT_AGAIN_MS = AWAIT_AGAIN.inWholeMilliseconds
        private val AWAITED_NEAR_MS = AWAITED_NEAR.inWholeMilliseconds
        private val UNDO_WINDOW_MS = UNDO_WINDOW.inWholeMilliseconds
        private val LATE_TAKE_MS = LATE_TAKE.inWholeMilliseconds
        private val LEFT_ON_WITHIN_MS = LEFT_ON_WITHIN.inWholeMilliseconds
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
