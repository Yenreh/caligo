@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.ArrivalPhase
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.nearbyStop
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The favorites' arrivals fading out, with answers scripted one request
 * after another on the test's virtual clock
 */
class StopsFadingTest {
    private val store = ViewModelStore()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** runTest, then the view models cleared, so no refresh is left on its pace */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    /** A fixed moment the virtual clock starts from */
    private val start = 1_794_330_000_000L

    private fun bus(vehicle: String, dueAt: Int, line: String = "E21") =
        BusArrival(line = line, destination = "Universidades", arrivalTime = start + dueAt * 1000L, vehicleId = vehicle)

    /**
     * A view model on [repository] whose clock runs with the test's, each
     * request answered by the next of [script]: the stops the service lists,
     * or null for a request that fails. Returns when each request was made,
     * in seconds of the test's clock.
     */
    private fun TestScope.scripted(
        repository: FakeStopsRepository,
        script: List<List<NearbyStop>?>,
    ): Pair<StopsViewModel, List<Long>> {
        val times = mutableListOf<Long>()
        repository.answer = {
            val answer = script[minOf(times.size, script.size - 1)]
            times.add(testScheduler.currentTime / 1000)
            repository.failure = if (answer == null) NetworkApiException("Connection error") else null
            if (answer != null) repository.nearby = answer
        }
        val stops = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { StopsViewModel(repository, now = { start + testScheduler.currentTime }) } },
        )[StopsViewModel::class]
        return stops to times
    }

    /** What the card of [id] reads now, as vehicle and phase */
    private fun TestScope.rows(stops: StopsViewModel, id: String) =
        stops.stops.value.fading[id]?.rows(start + testScheduler.currentTime).orEmpty().map { it.bus.vehicleId to it.phase }

    private val live = ArrivalPhase.Live
    private val atStop = ArrivalPhase.AtStop

    @Test
    fun aBusReachingTheStopFadesOutWhileTheRequestsKeepTheirPace() = test {
        val near = bus("1", dueAt = 90)
        val later = bus("2", dueAt = 840)
        val repository = FakeStopsRepository(favorites = listOf(favorite("a", 3.4516, -76.5320)), nearby = emptyList())
        val (stops, times) = scripted(
            repository,
            listOf(
                listOf(nearbyStop("a", listOf(near, later))),
                // A failed request: nothing leaves
                null,
                // The near bus has reached the stop
                listOf(nearbyStop("a", listOf(later))),
            ),
        )
        stops.startAutoRefresh(HOME, homeOnly = true)
        runCurrent()
        assertEquals(listOf("1" to live, "2" to live), rows(stops, "a"))

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(0L, 30L), times)
        assertEquals(listOf("1" to live, "2" to live), rows(stops, "a"))

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf("1" to atStop, "2" to live), rows(stops, "a"))
        // What paces the requests and is kept for the next launch is live alone
        assertEquals(listOf(later), stops.stops.value.arrivals["a"])
        assertEquals(mapOf("a" to listOf(later)), repository.kept)

        // Between answers the rows follow the clock: a minute past its time, passed
        advanceTimeBy(90.seconds)
        assertEquals(listOf("2" to live, "1" to ArrivalPhase.Passed(1)), rows(stops, "a"))
        advanceTimeBy(119.seconds)
        assertEquals(listOf("2" to live, "1" to ArrivalPhase.Passed(2)), rows(stops, "a"))
        advanceTimeBy(1.seconds)
        assertEquals(listOf("2" to live, "1" to ArrivalPhase.Passed(3)), rows(stops, "a"))
        // Four minutes past its time, gone
        advanceTimeBy(60.seconds)
        assertEquals(listOf("2" to live), rows(stops, "a"))

        // The requests are those the live buses alone set: every 30 s while
        // a bus is within 5 minutes, then every 60 s for one 13 minutes out
        assertEquals(listOf(0L, 30L, 60L, 120L, 180L, 240L, 300L), times)
    }

    @Test
    fun aBusThatLeavesFarFromItsTimeVanishesAsBefore() = test {
        val repository = FakeStopsRepository(favorites = listOf(favorite("a", 3.4516, -76.5320)), nearby = emptyList())
        val (stops, _) = scripted(
            repository,
            listOf(listOf(nearbyStop("a", listOf(bus("1", dueAt = 600)))), listOf(nearbyStop("a"))),
        )
        stops.startAutoRefresh(HOME, homeOnly = true)
        runCurrent()

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(emptyList<Pair<String, ArrivalPhase>>(), rows(stops, "a"))
    }

    @Test
    fun anAreaFavoriteRemembersEachOfItsStops() = test {
        val area = FavoriteStop("area", "Centro", anchorLatitude = 3.4484, anchorLongitude = -76.5300)
        val repository = FakeStopsRepository(favorites = listOf(area), nearby = emptyList())
        val (stops, _) = scripted(
            repository,
            listOf(
                listOf(nearbyStop("A1", listOf(bus("1", dueAt = 60))), nearbyStop("A2", listOf(bus("2", dueAt = 300)))),
                // The stop the last bus reached drops out of the answer
                listOf(nearbyStop("A2", listOf(bus("2", dueAt = 300)))),
            ),
        )
        // The first answer came as it loaded
        advanceTimeBy(30.seconds)
        stops.refreshArrivals(force = true).join()

        val fading = stops.stops.value.fading.getValue("area").rows(start + testScheduler.currentTime)
        assertEquals(listOf("A1" to atStop, "A2" to live), fading.map { it.stopId to it.phase })
    }

    @Test
    fun aFavoriteRemovedOrRelinkedDropsWhatItRemembered() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320), favorite("b", 3.4700, -76.5320)),
            nearby = listOf(nearbyStop("a", listOf(bus("1", dueAt = 60))), nearbyStop("b", listOf(bus("2", dueAt = 60)))),
        ).apply { applyUpdates = true }
        val (stops, _) = scripted(repository, listOf(repository.nearby))
        stops.refreshArrivals(force = true).join()
        assertEquals(setOf("a", "b"), stops.stops.value.fading.keys)

        stops.removeFavorite("b").join()
        assertNull(stops.stops.value.fading["b"])

        // Another stop, with no bus coming: the near bus remembered was the
        // old one's, and neither shows nor fades
        stops.relinkFavorite("a", NearbyStop(id = "z", name = "Stop z", distanceMeters = 40.0)).join()
        assertEquals(emptyList<BusArrival>(), stops.stops.value.arrivals["a"])
        assertEquals(emptyList<Pair<String, ArrivalPhase>>(), rows(stops, "a"))
    }

    @Test
    fun aFavoriteRelinkedWhileARefreshIsOnItsWayTakesNothingFromIt() = test {
        val old = bus("1", dueAt = 60)
        val new = bus("2", dueAt = 300)
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a", listOf(old)), nearbyStop("z", listOf(new))),
        ).apply { applyUpdates = true }
        val (stops, times) = scripted(repository, listOf(repository.nearby))
        assertEquals(listOf("1" to live), rows(stops, "a"))

        // A refresh held on its way while the favorite moves to another stop
        val gate = CompletableDeferred<Unit>()
        val script = repository.answer
        repository.answer = { gate.await() }
        val held = stops.refreshArrivals(force = true)
        stops.relinkFavorite("a", NearbyStop(id = "z", name = "Stop z", distanceMeters = 40.0)).join()
        repository.answer = script
        gate.complete(Unit)
        held.join()

        // Asked again at once, for the new stop: the old one's buses neither show nor fade
        assertEquals(listOf(new), stops.stops.value.arrivals["a"])
        assertEquals(listOf("2" to live), rows(stops, "a"))
        assertEquals(3, repository.nearbyCalls)
        assertEquals(listOf(0L, 0L), times)
        advanceTimeBy(30.seconds)
        stops.refreshArrivals(force = true).join()
        assertEquals(listOf("2" to live), rows(stops, "a"))
        assertEquals(listOf("z"), repository.saved.map { it.stopId })
    }

    private companion object {
        val HOME = Any()
    }
}
