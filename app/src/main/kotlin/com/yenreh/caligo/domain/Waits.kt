package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import kotlin.time.Duration.Companion.minutes

/**
 * One trip of one vehicle at one stop: what a wait follows, and how it is
 * told from the others. The service gives a vehicle the same id on its
 * next pass, so [trip], the time its row gave when the wait started,
 * tells the passes apart.
 */
data class WaitKey(val stopId: String, val vehicleId: String, val trip: Long)

/**
 * A wait running: the time its bus last gave, and when the answer that
 * last listed its trip was fetched, by which a row of that trip is known
 */
data class RunningWait(val key: WaitKey, val arrivalTime: Long, val listedAt: Long)

/**
 * The wait running for [bus]'s trip at [stopId], if any: not for a row of
 * its vehicle's next trip
 */
fun Collection<RunningWait>.waitFor(stopId: String, bus: BusArrival): WaitKey? = firstOrNull {
    it.key.stopId == stopId && it.key.vehicleId == bus.vehicleId &&
        !FadingArrivals.isNextTrip(it.arrivalTime, it.listedAt, bus.arrivalTime, it.listedAt)
}?.key

/**
 * The bus a rider waits for, as its row showed it when the bell was
 * tapped: one trip of one vehicle, at one stop.
 */
data class AwaitedBus(
    val stopId: String,
    /** The stop's name, for the notification: as the list it came from titles it */
    val stopName: String,
    val bus: BusArrival,
    /** Where the stop was found from: the favorite's anchor, or the point a list was asked around */
    val anchor: GeoPoint,
    /** Started from an area favorite: asked about around [anchor], whatever the stop's own place */
    val area: Boolean = false,
    /** The stop's own place, when the list knew it */
    val place: GeoPoint? = null,
) {
    val key: WaitKey get() = WaitKey(stopId, bus.vehicleId, bus.arrivalTime)
}

/**
 * Where a wait's stop is asked about: around the stop itself when its
 * place is known, so any answer within reach of it serves; otherwise
 * around the point it was found from, and only answers around that same
 * point serve
 */
data class AskPoint(val latitude: Double, val longitude: Double, val placed: Boolean)

/** An answer of the arrivals service, fetched at [fetchedAt], epoch milliseconds */
data class WaitAnswer(val stops: List<NearbyStop>, val fetchedAt: Long)

/** Every way a wait ends; the process going away is the tenth, and has no event */
enum class WaitEnding {
    /** 1: "Stop waiting" on the notification */
    STOPPED,

    /** 2: the notification swiped away */
    DISMISSED,

    /** 3: the filled bell tapped in the app */
    BELL,

    /** 4: "I took it" */
    TOOK,

    /** 5: the bus passed, and "Did you take it?" had its minute */
    PASSED,

    /** 6: the time limit */
    TIME_LIMIT,

    /** 7: the bus is no longer seen, said for a minute */
    NOT_SEEN,

    /** 8: no data for a while, said for a minute */
    NO_DATA,

    /** 9: notifications turned off for the app or its channel: every wait at once */
    BLOCKED,
}

/** What a wait says at a moment */
sealed interface WaitStatus {
    /**
     * On its way, due at [arrivalTime]; not [seen] when the latest answer
     * left it out while it was still far, as when five buses come first
     */
    data class Coming(val arrivalTime: Long, val seen: Boolean = true) : WaitStatus

    /** Reached the stop */
    data object AtStop : WaitStatus

    /** Passed: "Did you take it?" */
    data object Asking : WaitStatus

    /** The last request failed: no countdown that might be stale */
    data object NoData : WaitStatus

    /** Ending 7, said until the wait goes */
    data object NotSeen : WaitStatus

    /** Ending 8, said until the wait goes */
    data object NoDataEnded : WaitStatus
}

/**
 * A wait for one bus: what is known of it, read at a moment. Fed answers
 * and failures, read at [status] and [step]: what it says depends on
 * those alone, never on the clock running.
 */
data class Wait(
    val bus: AwaitedBus,
    val startedAt: Long,
    /** The stop's buses of recent answers, as part 1 fades them */
    val fading: FadingArrivals,
    /** The vehicle as its trip was last listed, or due as it reached the stop */
    val last: BusArrival = bus.bus,
    /** When the answer that last listed its trip was fetched */
    val listedAt: Long = startedAt,
    /** Its trip listed in the latest answer; false once left, or once its vehicle's next trip shows */
    val tripListed: Boolean = true,
    /** Its vehicle's next trip has shown: no later listing is this trip */
    val nextSeen: Boolean = false,
    /** Resolved at the first round */
    val point: AskPoint? = null,
    /** When an answer first left the vehicle out while it was far; null while listed */
    val missingSince: Long? = null,
    /** When the requests began to fail; null after an answer */
    val failingSince: Long? = null,
    /** When the last request was sent, epoch milliseconds */
    val askedAt: Long? = null,
    /** The ending being said, as of [tellingSince] */
    val telling: WaitEnding? = null,
    val tellingSince: Long = 0,
    /** The alert near the stop has gone out */
    val alerted: Boolean = false,
    /** Started from a row alone: no answer fed yet, whatever [fading] counts */
    val fromRow: Boolean = false,
    /**
     * When its trip reached the stop, as its vehicle's next trip first
     * showed while this one was no longer listed; null otherwise
     */
    val reachedAt: Long? = null,
) {
    val key: WaitKey get() = bus.key
    private val vehicle get() = bus.bus.vehicleId

    /** When the last answer fed was fetched; null before any */
    val answeredAt: Long? get() = if (fromRow) null else fading.lastAnswerFor(bus.stopId)

    /**
     * With [answer], which covers the stop: the stop missing from it has no
     * bus. An answer older than the last one fed changes nothing.
     */
    fun fed(answer: WaitAnswer): Wait {
        val known = fading.lastAnswerFor(bus.stopId)
        if (known != null && answer.fetchedAt < known) return this
        val at = answer.fetchedAt
        val buses = answer.stops.firstOrNull { it.id == bus.stopId }?.arrivals.orEmpty()
        val fading = fading.fed(bus.stopId, buses, at)
        val listing = buses.firstOrNull { it.vehicleId == vehicle }
        // Its own trip still, or its vehicle's next one
        val next = listing != null && (nextSeen || FadingArrivals.isNextTrip(last.arrivalTime, listedAt, listing.arrivalTime, at))
        if (listing != null && !next) {
            return copy(
                fading = fading,
                last = listing,
                listedAt = at,
                tripListed = true,
                missingSince = null,
                failingSince = null,
                fromRow = false,
                reachedAt = null,
            )
        }
        // Not listed: its trip fades, or, once lost from view, reached the stop as the next one showed
        val faded = fadedRow(fading, at)
        val reached = reachedAt ?: if (faded == null && next) minOf(last.arrivalTime, at) else null
        return copy(
            fading = fading,
            last = faded?.bus ?: last,
            tripListed = false,
            nextSeen = nextSeen || next,
            missingSince = if (faded != null || reached != null) null else missingSince ?: at,
            failingSince = null,
            fromRow = false,
            reachedAt = if (faded != null) null else reached,
        )
    }

    /**
     * The row of the trip waited for, at [now]: the vehicle's live row
     * while the trip is listed, else its fading one; never its next trip
     */
    private fun tripRow(fading: FadingArrivals, now: Long): FadingRow? {
        if (!tripListed) return fadedRow(fading, now)
        return fading.rows(now).firstOrNull { it.stopId == bus.stopId && it.bus.vehicleId == vehicle && it.phase == ArrivalPhase.Live }
    }

    private fun fadedRow(fading: FadingArrivals, now: Long) = fading.rows(now).firstOrNull {
        it.stopId == bus.stopId && it.bus.vehicleId == vehicle && it.phase != ArrivalPhase.Live
    }

    /** Whether [other] is a row of the trip waited for, at [stopId], and not of its vehicle's next trip */
    fun follows(stopId: String, other: BusArrival): Boolean = stopId == bus.stopId && other.vehicleId == vehicle &&
        !FadingArrivals.isNextTrip(last.arrivalTime, listedAt, other.arrivalTime, listedAt)

    /** A request failed at [now] */
    fun failed(now: Long): Wait = copy(failingSince = failingSince ?: now)

    /** What it says at [now] */
    fun status(now: Long): WaitStatus {
        when (telling) {
            WaitEnding.NOT_SEEN -> return WaitStatus.NotSeen
            WaitEnding.NO_DATA -> return WaitStatus.NoDataEnded
            else -> {}
        }
        reachedAt?.let { reached ->
            return if (now - reached < FadingArrivals.AT_STOP_FOR.inWholeMilliseconds) WaitStatus.AtStop else WaitStatus.Asking
        }
        val row = tripRow(fading, now)
        return when (row?.phase) {
            ArrivalPhase.AtStop -> WaitStatus.AtStop
            is ArrivalPhase.Passed -> WaitStatus.Asking
            else -> when {
                failingSince != null -> WaitStatus.NoData
                else -> WaitStatus.Coming(last.arrivalTime, seen = row != null || missingSince == null)
            }
        }
    }

    /** At [now]: the wait as it goes on, or how it ends */
    fun step(now: Long): WaitStep {
        if (now >= endsBy) return WaitStep.Ends(WaitEnding.TIME_LIMIT)
        telling?.let { ending ->
            return if (now >= tellingSince + TELL_FOR_MS) WaitStep.Ends(ending) else WaitStep.Goes(this)
        }
        val status = status(now)
        val due = reachedAt ?: last.arrivalTime
        if (status == WaitStatus.Asking && now >= due + FadingArrivals.AT_STOP_FOR.inWholeMilliseconds + ASK_FOR_MS) {
            return WaitStep.Ends(WaitEnding.PASSED)
        }
        val failing = failingSince
        if (failing != null && now >= failing + NO_DATA_FOR_MS) return WaitStep.Goes(tell(WaitEnding.NO_DATA, now))
        val missing = missingSince
        if (status is WaitStatus.Coming && missing != null &&
            (now >= missing + MISSING_FOR_MS || now >= last.arrivalTime + WELL_PAST_MS)
        ) {
            return WaitStep.Goes(tell(WaitEnding.NOT_SEEN, now))
        }
        return WaitStep.Goes(this)
    }

    /**
     * When it ends at the latest: [TIME_LIMIT] after it started, or
     * [PAST_DUE] after the time its bus gave then, whichever is later, so
     * a bus listed far ahead still gets its wait
     */
    val endsBy: Long get() = maxOf(startedAt + TIME_LIMIT_MS, bus.bus.arrivalTime + PAST_DUE_MS)

    private fun tell(ending: WaitEnding, now: Long) = copy(telling = ending, tellingSince = now)

    /** Whether rounds still ask about it: not once it only says how it ended, nor while asking */
    fun asks(now: Long): Boolean = telling == null && status(now) != WaitStatus.Asking

    /** Whether the alert near the stop is due at [now] and has not gone out */
    fun alertDue(now: Long): Boolean {
        if (alerted || telling != null) return false
        return when (val status = status(now)) {
            WaitStatus.AtStop -> true
            is WaitStatus.Coming -> status.seen && status.arrivalTime - now <= ALERT_WITHIN_MS
            else -> false
        }
    }

    /** The time that paces its rounds: its bus, or now once due */
    fun paceTime(now: Long): Long = maxOf(last.arrivalTime, now)

    companion object {
        /** Started from [bus], seeded with what the row's list knew of its stop, if anything */
        fun start(bus: AwaitedBus, now: Long, seed: FadingArrivals? = null): Wait {
            val known = seed?.only(setOf(bus.stopId))
            // The row tapped, as its list reads it: live, or already at the stop
            val row = known?.rows(now)?.firstOrNull { it.bus == bus.bus }
            if (known != null && row != null) {
                return Wait(
                    bus,
                    now,
                    known,
                    listedAt = known.lastAnswerFor(bus.stopId) ?: now,
                    tripListed = row.phase == ArrivalPhase.Live,
                )
            }
            // Without a list that knew it, the row counts as listed now, and is asked about at once
            return Wait(bus, now, FadingArrivals.EMPTY.fed(bus.stopId, listOf(bus.bus), now), fromRow = true)
        }

        /** Waits at once, at most */
        const val CAP = 5

        /** How long a wait lasts at most, unless its bus was due later: see [Wait.endsBy] */
        val TIME_LIMIT = 45.minutes

        /** How long past the time its bus gave at the start a wait lasts at least */
        val PAST_DUE = 10.minutes

        /** How long "Did you take it?" asks once the bus passed */
        val ASK_FOR = 1.minutes

        /**
         * How long a far bus may be missing from the answers before it is
         * no longer seen: one pushed out by five buses coming first came
         * back within minutes in the samples
         */
        val MISSING_FOR = 5.minutes

        /** How far past its last known time a missing bus is no longer seen */
        val WELL_PAST = 2.minutes

        /** How long the requests may fail before the wait ends */
        val NO_DATA_FOR = 5.minutes

        /** How long an ending is said before the wait goes */
        val TELL_FOR = 1.minutes

        /** How close the bus is when the alert goes out */
        val ALERT_WITHIN = 2.minutes

        private val TIME_LIMIT_MS = TIME_LIMIT.inWholeMilliseconds
        private val PAST_DUE_MS = PAST_DUE.inWholeMilliseconds
        private val ASK_FOR_MS = ASK_FOR.inWholeMilliseconds
        private val MISSING_FOR_MS = MISSING_FOR.inWholeMilliseconds
        private val WELL_PAST_MS = WELL_PAST.inWholeMilliseconds
        private val NO_DATA_FOR_MS = NO_DATA_FOR.inWholeMilliseconds
        private val TELL_FOR_MS = TELL_FOR.inWholeMilliseconds
        private val ALERT_WITHIN_MS = ALERT_WITHIN.inWholeMilliseconds
    }
}

/** What a wait does at a moment */
sealed interface WaitStep {
    data class Goes(val wait: Wait) : WaitStep

    data class Ends(val ending: WaitEnding) : WaitStep
}
