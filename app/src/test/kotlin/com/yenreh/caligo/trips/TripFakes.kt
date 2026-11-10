package com.yenreh.caligo.trips

import com.yenreh.caligo.data.location.FixQuality
import com.yenreh.caligo.data.location.LocationUpdates
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.TookRide
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
    var arrivalsAnswer: (stops: List<LineStop>, at: Long) -> LiveArrivals = { _, _ -> LiveArrivals.NONE }
    val arrivalsAsked = mutableListOf<List<String>>()
    val observed = mutableListOf<Map<String, Double>>()

    override suspend fun buses(line: String): List<LineBus> {
        val at = now()
        asked.add(at)
        askedLines.add(line)
        return byLine?.invoke(line, at) ?: answer(at)
    }

    override suspend fun arrivals(stops: List<LineStop>): LiveArrivals {
        arrivalsAsked.add(stops.map { it.stopId })
        return arrivalsAnswer(stops, now())
    }

    override suspend fun observeRides(readings: Map<String, Double>) {
        observed.add(readings)
    }
}

/** The trip as the screens start, change and finish it, recorded */
class FakeTripControl(private val now: Long = TripFixtures.NOW) : TripControl {
    override val running = MutableStateFlow<Journey?>(null)
    override val ended = MutableStateFlow<EndedTrip?>(null)
    val starts = mutableListOf<TripStart>()
    val started: List<TripPlan> get() = starts.filterIsInstance<TripStart.Ride>().map { it.plan }
    val alights = mutableListOf<Int?>()
    val tookRides = mutableListOf<TookRide>()
    var finished = 0

    override fun start(start: TripStart) {
        starts.add(start)
        running.value = start.journey(now)
    }

    override fun took(took: TookRide): Boolean {
        val journey = running.value ?: return false
        if (!journey.planned || !journey.awaits(took.line, took.stopId)) return false
        tookRides.add(took)
        running.value = journey.took(took.line, now)
        return true
    }

    override fun alightAt(alight: Int?) {
        alights.add(alight)
        running.value = running.value?.let { j -> j.copy(ride = j.ride?.alightAt(alight, now)) }
    }

    override fun finish() {
        finished++
        running.value = null
    }
}
