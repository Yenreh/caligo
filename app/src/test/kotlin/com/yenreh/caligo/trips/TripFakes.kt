package com.yenreh.caligo.trips

import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripFix
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import kotlinx.coroutines.flow.MutableStateFlow

/** The device's fixes as a test gives them, each request recorded */
class FakeLocationUpdates : LocationUpdates {
    var usable = true
    override fun usable() = usable

    /** The permission the precise one; false with the approximate alone */
    var precise = true
    override fun precise() = precise

    override var requested: FixQuality? = null
        private set

    /** Every quality asked for, in order */
    val qualities = mutableListOf<FixQuality>()

    private var onFix: ((TripFix) -> Unit)? = null
    private var onLost: (() -> Unit)? = null

    override fun request(quality: FixQuality, onFix: (TripFix) -> Unit, onLost: () -> Unit) {
        requested = quality
        qualities.add(quality)
        this.onFix = onFix
        this.onLost = onLost
    }

    override fun stop() {
        requested = null
        onFix = null
        onLost = null
    }

    /** A fix comes, if any is asked for */
    fun emit(fix: TripFix) {
        onFix?.invoke(fix)
    }

    /** Location goes off */
    fun lose() {
        usable = false
        onLost?.invoke()
    }
}

/**
 * The line's buses as a test scripts them, by the time asked, or by line
 * and time when [byLine] is set; throwing fails the request. The
 * arrivals of a ride's stops, and what the trip teaches, recorded.
 */
class FakeTripSource(private val now: () -> Long) : TripSource {
    var answer: (Long) -> List<LineBus> = { emptyList() }
    var byLine: ((line: String, at: Long) -> List<LineBus>)? = null
    val asked = mutableListOf<Long>()
    val askedLines = mutableListOf<String>()

    /** Where each request said the trip tells its buses */
    val askedNear = mutableListOf<GeoPoint?>()
    var arrivalsAnswer: (stops: List<LineStop>, at: Long) -> LiveArrivals = { _, _ -> LiveArrivals.NONE }
    val arrivalsAsked = mutableListOf<List<String>>()

    /** When each was asked */
    val arrivalsAskedAt = mutableListOf<Long>()
    val observed = mutableListOf<Map<String, Double>>()

    override suspend fun buses(line: String, near: GeoPoint?): List<LineBus> {
        val at = now()
        asked.add(at)
        askedLines.add(line)
        askedNear.add(near)
        return byLine?.invoke(line, at) ?: answer(at)
    }

    override suspend fun arrivals(stops: List<LineStop>): LiveArrivals {
        arrivalsAsked.add(stops.map { it.stopId })
        arrivalsAskedAt.add(now())
        return arrivalsAnswer(stops, now())
    }

    /** While set and not complete, what the rides teach waits for it, as a slow disk */
    var learnGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun observeRides(readings: Map<String, Double>) {
        learnGate?.await()
        observed.add(readings)
    }
}

/**
 * The trip as the screens start, change and finish it, recorded: as
 * [TripDesk] with its service running, a start replaces the trip on, or
 * finishes one finishing, a Finish shows it finished until Undo or
 * [expireFinish], each as [ended] says, and a bus is taken by the trip's
 * own rules, never by one finishing
 */
class FakeTripControl(private val now: Long = TripFixtures.NOW) : TripControl {
    override val running = MutableStateFlow<Journey?>(null)
    override val ended = MutableStateFlow<EndedTrip?>(null)
    override val finishing = MutableStateFlow<FinishingTrip?>(null)
    val starts = mutableListOf<TripStart>()
    val started: List<TripPlan> get() = starts.filterIsInstance<TripStart.Ride>().map { it.plan }
    val alights = mutableListOf<Int?>()
    val tookRides = mutableListOf<TookRide>()
    override val awaited = MutableStateFlow<List<AwaitedLine>>(emptyList())
    val takenLines = mutableListOf<String>()
    var finished = 0

    override fun start(start: TripStart) {
        starts.add(start)
        ended.value = finishing.value?.let { EndedTrip(it.journey, TripEnding.FINISHED) }
            ?: running.value?.let { EndedTrip(it, TripEnding.REPLACED) }
        finishing.value = null
        running.value = start.journey(now)
    }

    override fun took(took: TookRide): Boolean {
        if (finishing.value != null) return false
        val taken = running.value?.tookIt(took, now) ?: return false
        tookRides.add(took)
        running.value = taken
        return true
    }

    override fun takeLine(line: String) {
        takenLines.add(line)
        running.value?.tookLine(line, now)?.let { running.value = it }
    }

    override fun alightAt(alight: Int?) {
        alights.add(alight)
        running.value = running.value?.let { j -> j.copy(ride = j.ride?.alightAt(alight, now)) }
    }

    override fun finish() {
        finished++
        val journey = running.value ?: return
        running.value = null
        ended.value = EndedTrip(journey, TripEnding.FINISHED)
        finishing.value = FinishingTrip(journey, now + TripEngine.UNDO_WINDOW.inWholeMilliseconds)
    }

    var undone = 0

    override fun undoFinish() {
        undone++
        val journey = finishing.value?.journey ?: return
        finishing.value = null
        ended.value = null
        running.value = journey
    }

    /** The Finish's window runs out: the trip stays finished */
    fun expireFinish() {
        finishing.value = null
    }

    /** Whether a trip may post, as the trips' channel says */
    var allowed = true
    var settingsOpened = 0

    override fun allowed() = allowed

    override fun openSettings() {
        settingsOpened++
    }
}
