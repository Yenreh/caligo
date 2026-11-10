package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.BusArrival
import kotlin.time.Duration.Companion.minutes

/** What an arrival row says at a moment */
sealed interface ArrivalPhase {
    /** Listed in the latest answer: its countdown */
    data object Live : ArrivalPhase

    /** Left the answers when due, and not a minute past its time yet */
    data object AtStop : ArrivalPhase

    /** Gone by, [minutes] whole minutes past its time */
    data class Passed(val minutes: Int) : ArrivalPhase
}

/** A bus on a list of arrivals, at the stop [stopId], as it reads at a moment */
data class FadingRow(val bus: BusArrival, val stopId: String, val phase: ArrivalPhase)

/**
 * The buses of recent answers for one stop, or for the stops of an area,
 * so that a bus reaching its stop fades out instead of vanishing.
 *
 * Fed answers, read at a moment: what it shows depends on those two
 * alone. A bus leaves only when an answer covering its stop no longer
 * lists it, which includes the stop itself missing: the service leaves
 * out stops with no bus coming. One that leaves within [NEAR] of its time,
 * as last listed, is [ArrivalPhase.AtStop] up to [AT_STOP_FOR] past that
 * time, then [ArrivalPhase.Passed] until [GONE_AFTER]; one that leaves
 * while further away goes at once, as it may have changed route or lost
 * its signal. Listed again, a bus is live again with its new time.
 *
 * A bus is told by its vehicle within its stop: the service keeps a
 * vehicle's id from one answer to the next, and lists one vehicle at
 * several stops at once. One without an id goes at once.
 *
 * The service also keeps a vehicle's id for its next pass at the same
 * stop, about one loop later, and lists it as it reaches the stop, at
 * times without leaving the list in between. A listing that [isNextTrip]
 * is that next trip, a row of its own: the trip before has reached the
 * stop, and fades as one that left near its time, due at the earlier of
 * its last time and the moment the next trip first shows. A trip fading
 * stays until it goes, whatever its vehicle's next trip does.
 */
class FadingArrivals private constructor(
    private val seen: List<Seen>,
    /** When the last answer fed for each stop was fetched, epoch milliseconds */
    private val answeredAt: Map<String, Long>,
) {
    /**
     * A bus as last listed: the answer listing it was fetched at
     * [listedAt]; [listed] while the latest answer for its stop lists it
     */
    private class Seen(val stopId: String, val bus: BusArrival, val listedAt: Long, val listed: Boolean)

    /**
     * With the answer for [stopId], fetched at [fetchedAt]: its [buses],
     * none when the answer leaves the stop out. An answer older than the
     * last one fed for that stop changes nothing, and the same answer fed
     * again, as a reused area is, leaves everything as it was.
     */
    fun fed(stopId: String, buses: List<BusArrival>, fetchedAt: Long): FadingArrivals {
        val last = answeredAt[stopId]
        if (last != null && fetchedAt < last) return this
        val (before, others) = seen.partition { it.stopId == stopId }
        val listed = buses.map { Seen(stopId, it, fetchedAt, listed = true) }
        val left = before.mapNotNull { earlier ->
            val vehicle = earlier.bus.vehicleId
            val time = earlier.bus.arrivalTime
            // Its vehicle listed again: this trip still, or its next one
            val again = if (vehicle.isEmpty()) emptyList() else buses.filter { it.vehicleId == vehicle }
            when {
                again.any { !isNextTrip(time, earlier.listedAt, it.arrivalTime, fetchedAt) } -> null
                // Never seen in the samples; such a bus could not be told from the next
                vehicle.isEmpty() -> null
                // Its next trip shows: this one reached the stop, however far it seemed
                earlier.listed && again.isNotEmpty() -> {
                    val reached = earlier.bus.copy(arrivalTime = minOf(time, fetchedAt))
                    if (fetchedAt >= reached.arrivalTime + GONE_AFTER_MS) null else Seen(stopId, reached, earlier.listedAt, listed = false)
                }
                earlier.listed && time - earlier.listedAt > NEAR_MS -> null
                fetchedAt >= time + GONE_AFTER_MS -> null
                earlier.listed -> Seen(stopId, earlier.bus, earlier.listedAt, listed = false)
                else -> earlier
            }
        }
        return FadingArrivals(others + listed + left, answeredAt + (stopId to fetchedAt))
    }

    /**
     * With an answer fetched at [fetchedAt] that covers every stop this
     * remembers, as one asked around a favorite does: by stop id, the
     * buses of each stop it lists. A stop remembered but not listed has no
     * bus left.
     */
    fun fedWhole(stops: Map<String, List<BusArrival>>, fetchedAt: Long): FadingArrivals {
        var next = this
        val ids = LinkedHashSet(stops.keys)
        seen.mapTo(ids) { it.stopId }
        for (id in ids) next = next.fed(id, stops[id].orEmpty(), fetchedAt)
        // Only the stops still answered or remembered are worth a time
        val kept = next.seen.mapTo(HashSet(stops.keys)) { it.stopId }
        return FadingArrivals(next.seen, next.answeredAt.filterKeys { it in kept })
    }

    /**
     * The rows at [now], epoch milliseconds: at the stop first, then the
     * live ones by time, then those passed, the most recent first
     */
    fun rows(now: Long): List<FadingRow> {
        val atStop = mutableListOf<FadingRow>()
        val live = mutableListOf<FadingRow>()
        val passed = mutableListOf<FadingRow>()
        for (s in seen) {
            if (s.listed) {
                live.add(FadingRow(s.bus, s.stopId, ArrivalPhase.Live))
                continue
            }
            val since = now - s.bus.arrivalTime
            when {
                since < AT_STOP_FOR_MS -> atStop.add(FadingRow(s.bus, s.stopId, ArrivalPhase.AtStop))
                since < GONE_AFTER_MS -> {
                    val minutes = (since / MINUTE_MS).toInt().coerceIn(1, (GONE_AFTER_MS / MINUTE_MS).toInt() - 1)
                    passed.add(FadingRow(s.bus, s.stopId, ArrivalPhase.Passed(minutes)))
                }
            }
        }
        atStop.sortBy { it.bus.arrivalTime }
        live.sortBy { it.bus.arrivalTime }
        passed.sortByDescending { it.bus.arrivalTime }
        return atStop + live + passed
    }

    /**
     * Buses that left near their time and are still at the stop at [now],
     * by stop id: those a rider reaching the stop by [AT_STOP_FOR] past
     * their time could still catch
     */
    fun atTheStop(now: Long): Map<String, List<BusArrival>> = seen
        .filter { !it.listed && now - it.bus.arrivalTime < AT_STOP_FOR_MS }
        .groupBy({ it.stopId }, { it.bus })

    /** Only what is remembered of [stopIds] */
    fun only(stopIds: Set<String>): FadingArrivals =
        FadingArrivals(seen.filter { it.stopId in stopIds }, answeredAt.filterKeys { it in stopIds })

    /** When the last answer fed for [stopId] was fetched, epoch milliseconds; null before any */
    fun lastAnswerFor(stopId: String): Long? = answeredAt[stopId]

    /** Whether nothing is remembered */
    val isEmpty: Boolean get() = seen.isEmpty()

    companion object {
        val EMPTY = FadingArrivals(emptyList(), emptyMap())

        /** A bus leaving the answers this close to its time has reached the stop */
        val NEAR = 2.minutes

        /** How long past its time a bus that reached the stop is still there */
        val AT_STOP_FOR = 1.minutes

        /** How long past its time a bus that reached the stop stays listed, greyed */
        val GONE_AFTER = 4.minutes

        /**
         * How much later than any delay could make it a listing of a
         * vehicle is its next trip, and how close its trip before was
         * to the stop: see [isNextTrip]
         */
        val NEXT_TRIP_MARGIN = 5.minutes

        /**
         * Whether a vehicle that gave [previous] at a stop, in the answer
         * fetched at [listedAt], listed there again at [listing] in the one
         * fetched at [at], is on its next trip rather than the same one
         * late. A delay builds up answer by answer, about as fast as the
         * clock at most, so only the next pass jumps by more than the time
         * between the answers plus [NEXT_TRIP_MARGIN]; and only a trip due
         * within [NEXT_TRIP_MARGIN] of its last listing was about to end. A
         * bus stuck far away drifting later stays the same trip. In the
         * samples, 1447 pairs of answers: changes up to +82 s at p99, +537
         * s at p99.9; the next passes +45.2 and +42.1 min in 30 s, due
         * within 2.1 min.
         */
        fun isNextTrip(previous: Long, listedAt: Long, listing: Long, at: Long): Boolean =
            previous - listedAt <= NEXT_TRIP_MARGIN_MS &&
                listing - previous > (at - listedAt).coerceAtLeast(0) + NEXT_TRIP_MARGIN_MS

        /** Passed rows shown on top of a list's limit, the most recent */
        const val MAX_PASSED = 2

        private const val MINUTE_MS = 60_000L
        private val NEAR_MS = NEAR.inWholeMilliseconds
        private val AT_STOP_FOR_MS = AT_STOP_FOR.inWholeMilliseconds
        private val GONE_AFTER_MS = GONE_AFTER.inWholeMilliseconds
        private val NEXT_TRIP_MARGIN_MS = NEXT_TRIP_MARGIN.inWholeMilliseconds
    }
}

/** These buses as an answer lists them, at a stop not told: rows with nothing faded */
fun List<BusArrival>.asLiveRows(): List<FadingRow> = map { FadingRow(it, stopId = "", phase = ArrivalPhase.Live) }

/**
 * The rows a list of [limit] shows: those at the stop and live up to it,
 * and on top of it the [passed] most recent that passed
 */
fun List<FadingRow>.shown(limit: Int, passed: Int = FadingArrivals.MAX_PASSED): List<FadingRow> {
    val (gone, coming) = partition { it.phase is ArrivalPhase.Passed }
    return coming.take(limit) + gone.take(passed)
}
