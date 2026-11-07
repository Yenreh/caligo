@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.data.json.JsonTypeException
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.nearbyStop
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The home's cases of stops_provider_test.dart, plus the fix that ends a
 * refresh on any error. The view model runs on the test's main dispatcher,
 * eager as Dart's synchronous start of an async call.
 */
class StopsViewModelTest {
    private val store = ViewModelStore()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /**
     * runTest, then the view models cleared: a refresh left on its pace
     * would otherwise keep the test's virtual clock running for ever
     */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: FakeStopsRepository,
        ready: suspend () -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
    ): StopsViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { StopsViewModel(repository, ready, now) } },
    )[StopsViewModel::class]

    /**
     * Let the load the view model starts on its own finish, then load
     * again, so a test counts only what it does itself: _readyNotifier
     */
    private suspend fun ready(repository: FakeStopsRepository) = viewModel(repository).also {
        it.loadFavorites().join()
    }

    private val StopsViewModel.state get() = stops.value

    // StopsNotifier.refreshArrivals

    @Test
    fun answersStopsSavedFromTheSamePlaceWithOneRequest() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(
                // Two platforms saved from the same spot, one street away
                favorite("a", 3.4516, -76.5320),
                favorite("b", 3.4516, -76.5320),
                favorite("c", 3.4700, -76.5320),
            ),
            nearby = listOf(nearbyStop("a"), nearbyStop("b"), nearbyStop("c")),
        )
        val stops = ready(repository)

        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()

        assertEquals(2, repository.nearbyCalls)
    }

    @Test
    fun theHomeOnlyAsksForTheStopsItLists() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(
                favorite("shown", 3.4516, -76.5320),
                favorite("hidden", 3.4700, -76.5320).copy(showOnHome = false),
            ),
            nearby = listOf(nearbyStop("shown"), nearbyStop("hidden")),
        )
        val stops = ready(repository)

        stops.startAutoRefresh(HOME, homeOnly = true)
        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()

        // The two stops are too far apart to share a request
        assertEquals(1, repository.nearbyCalls)
    }

    @Test
    fun asksForEveryStopWhileAnyScreenWatchingWantsThemAll() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(
                favorite("shown", 3.4516, -76.5320),
                favorite("hidden", 3.4700, -76.5320).copy(showOnHome = false),
            ),
            nearby = listOf(nearbyStop("shown"), nearbyStop("hidden")),
        )
        val stops = ready(repository)

        stops.startAutoRefresh(HOME, homeOnly = true)
        stops.startAutoRefresh(STOPS_SCREEN)
        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()
        assertEquals(2, repository.nearbyCalls)

        // The stops screen closed: the home is left, with its stops
        stops.stopAutoRefresh(STOPS_SCREEN)
        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()
        assertEquals(1, repository.nearbyCalls)
    }

    @Test
    fun asksAgainTooSoonOnlyForTheStopsWithNothingToShow() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(
                favorite("shown", 3.4516, -76.5320),
                favorite("hidden", 3.4700, -76.5320).copy(showOnHome = false),
            ),
            nearby = listOf(nearbyStop("shown"), nearbyStop("hidden")),
        )

        // The home watches from the start, so only its stop loads
        val started = Job()
        val stops = viewModel(repository, ready = { started.join() })
        stops.startAutoRefresh(HOME, homeOnly = true)
        started.complete()
        stops.loadFavorites().join()
        assertEquals(listOf("shown"), stops.state.arrivals.keys.toList())

        // The stops screen opens right after: its extra stop has nothing yet
        stops.startAutoRefresh(STOPS_SCREEN)
        repository.nearbyCalls = 0
        stops.refreshArrivals().join()
        assertEquals(2, repository.nearbyCalls)

        // Everything shows something now: too soon to ask again
        stops.refreshArrivals().join()
        assertEquals(2, repository.nearbyCalls)
    }

    @Test
    fun placedStopsShareARequestWhenOnePointReachesBoth() = test {
        // Saved from two points 400 m apart, about 220 m from each other
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320), favorite("b", 3.4552, -76.5320)),
            nearby = listOf(nearbyStop("a"), nearbyStop("b")),
            positions = mapOf("a" to GeoPoint(3.4525, -76.5320), "b" to GeoPoint(3.4545, -76.5320)),
        )
        val stops = ready(repository)

        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()

        assertEquals(1, repository.nearbyCalls)
    }

    @Test
    fun aPlacedStopTheSharedAnswerLeavesOutHasNoBusComing() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320), favorite("quiet", 3.4540, -76.5320)),
            nearby = listOf(nearbyStop("a")),
            positions = mapOf("a" to GeoPoint(3.4520, -76.5320), "quiet" to GeoPoint(3.4530, -76.5320)),
        )
        val stops = ready(repository)

        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()

        // Within reach of the point asked around: no second request
        assertEquals(1, repository.nearbyCalls)
        assertEquals(emptyList<BusArrival>(), stops.state.arrivals["quiet"])
    }

    @Test
    fun anAreaIsStillAskedAboutAroundItsOwnAnchor() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(
                FavoriteStop(id = "station-1", name = "Station", anchorLatitude = 3.4516, anchorLongitude = -76.5320),
            ),
            nearby = listOf(nearbyStop("a")),
            positions = mapOf("a" to GeoPoint(3.4600, -76.5320)),
        )
        val stops = ready(repository)

        repository.askedAround.clear()
        stops.refreshArrivals(force = true).join()

        assertEquals(listOf(GeoPoint(3.4516, -76.5320)), repository.askedAround)
    }

    @Test
    fun asksAgainOnlyWhenTheStopIsAnchoredSomewhereElse() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320), favorite("missing", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a")),
        )
        val stops = ready(repository)

        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()

        // The same anchor gives the same answer: asking twice is waste
        assertEquals(1, repository.nearbyCalls)
    }

    @Test
    fun skipsARefreshThatFollowsAnotherTooClosely() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a")),
        )
        val stops = ready(repository)

        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()
        stops.refreshArrivals().join()

        assertEquals(1, repository.nearbyCalls)
    }

    // StopsNotifier arrivals

    @Test
    fun handsEachFavoriteTheArrivalsOfItsOwnStop() = test {
        val arrival = BusArrival(
            line = "T31",
            destination = "Universidades",
            arrivalTime = at(2026, 1, 1, 12, 0),
            vehicleId = "1",
            stopName = "Stop a",
        )
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a", arrivals = listOf(arrival)), nearbyStop("b")),
        )
        val stops = ready(repository)

        stops.refreshArrivals(force = true).join()

        assertEquals(listOf(arrival), stops.state.arrivals["a"])
    }

    @Test
    fun anAreaFavoriteGathersEveryStopAroundItsAnchor() = test {
        val later = BusArrival(line = "A1", destination = "X", arrivalTime = at(2026, 1, 1, 12, 5), vehicleId = "1")
        val sooner = BusArrival(line = "A2", destination = "Y", arrivalTime = at(2026, 1, 1, 12, 0), vehicleId = "2")
        val repository = FakeStopsRepository(
            favorites = listOf(
                FavoriteStop(id = "station-1", name = "Station", anchorLatitude = 3.4516, anchorLongitude = -76.5320),
            ),
            nearby = listOf(nearbyStop("a", arrivals = listOf(later)), nearbyStop("b", arrivals = listOf(sooner))),
        )
        val stops = ready(repository)

        stops.refreshArrivals(force = true).join()

        // Both platforms, soonest first
        assertEquals(listOf(sooner, later), stops.state.arrivals["station-1"])
    }

    // StopsNotifier reconciliation

    @Test
    fun countsAStopTheServiceStopsReporting() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("gone", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("other")),
        )
        val stops = ready(repository)
        repository.saved.clear()

        stops.refreshArrivals(force = true).join()
        assertEquals(1, repository.saved.single().missingCount)
        assertFalse(stops.state.favorites.single().looksGone)

        stops.refreshArrivals(force = true).join()
        stops.refreshArrivals(force = true).join()

        val favorite = stops.state.favorites.single()
        assertEquals(FavoriteStop.MISSING_THRESHOLD, favorite.missingCount)
        assertTrue(favorite.looksGone)
    }

    @Test
    fun countsAnAreaAroundWhichNothingAnswers() = test {
        val area = FavoriteStop(id = "station-1", name = "Station", anchorLatitude = 3.4516, anchorLongitude = -76.5320)
        val repository = FakeStopsRepository(favorites = listOf(area), nearby = emptyList())
        val stops = ready(repository)
        repository.saved.clear()

        stops.refreshArrivals(force = true).join()

        // The Flutter app always found an area, so it never counted as missing
        assertEquals(1, repository.saved.single().missingCount)
        assertEquals(emptyList<BusArrival>(), stops.state.arrivals["station-1"])
    }

    @Test
    fun anAreaWithAnyStopAnsweringIsNotMissing() = test {
        val area = FavoriteStop(
            id = "station-1",
            name = "Station",
            anchorLatitude = 3.4516,
            anchorLongitude = -76.5320,
            missingCount = 2,
        )
        val repository = FakeStopsRepository(favorites = listOf(area), nearby = listOf(nearbyStop("a")))
        val stops = ready(repository)

        stops.refreshArrivals(force = true).join()

        assertEquals(0, stops.state.favorites.single().missingCount)
    }

    @Test
    fun picksUpAStopThatWasRenamed() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(NearbyStop(id = "a", name = "Kr 27 con Cl 122", distanceMeters = 5.0)),
        )
        val stops = ready(repository)
        repository.saved.clear()

        stops.refreshArrivals(force = true).join()

        assertEquals("Kr 27 con Cl 122", repository.saved.single().name)
        assertEquals("Kr 27 con Cl 122", stops.state.favorites.single().name)
    }

    @Test
    fun leavesAFavoriteAloneWhileNothingChanges() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a")),
        )
        val stops = ready(repository)
        repository.saved.clear()

        stops.refreshArrivals(force = true).join()

        assertTrue(repository.saved.isEmpty())
    }

    // StopsNotifier.nextRefreshDelay

    private val noon = at(2026, 9, 28, 12, 0)

    private fun inMinutes(minutes: Int) = BusArrival(
        line = "A47",
        destination = "Valle Grande",
        arrivalTime = noon + minutes.minutes.inWholeMilliseconds,
        vehicleId = "1",
    )

    @Test
    fun followsABusCloselyWhenItIsAboutToCome() {
        assertEquals(
            30.seconds,
            StopsViewModel.nextRefreshDelay(listOf(listOf(inMinutes(20), inMinutes(4))), noon),
        )
    }

    @Test
    fun easesOffAsTheNextBusGetsFurtherAway() {
        assertEquals(60.seconds, StopsViewModel.nextRefreshDelay(listOf(listOf(inMinutes(12))), noon))
        assertEquals(120.seconds, StopsViewModel.nextRefreshDelay(listOf(listOf(inMinutes(25))), noon))
    }

    @Test
    fun waitsLongestWithNoBusComingIgnoringBusesAlreadyGone() {
        assertEquals(
            120.seconds,
            StopsViewModel.nextRefreshDelay(listOf(emptyList(), listOf(inMinutes(-3))), noon),
        )
    }

    // StopsNotifier kept arrivals

    @Test
    fun showsTheLastArrivalsAtOnceWithoutTheBusesAlreadyGone() = test {
        val now = System.currentTimeMillis()
        fun bus(line: String, fromNow: kotlin.time.Duration) = BusArrival(
            line = line,
            destination = "Valle Grande",
            arrivalTime = now + fromNow.inWholeMilliseconds,
            vehicleId = line,
        )
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320), favorite("b", 3.4700, -76.5320)),
            // Nothing answers, so only the kept arrivals can show
            nearby = emptyList(),
        ).apply {
            kept = mapOf(
                "a" to listOf(bus("gone", (-2).minutes), bus("next", 6.minutes)),
                "b" to listOf(bus("gone", (-1).minutes)),
                "removed" to listOf(bus("next", 3.minutes)),
            )
        }

        // The first arrivals to show are the kept ones, there as the refresh
        // that replaces them sets off
        val started = Job()
        val stops = viewModel(repository, ready = { started.join() })
        var shown: Map<String, List<BusArrival>>? = null
        repository.answer = { if (shown == null) shown = stops.stops.value.arrivals }
        started.complete()

        assertEquals(listOf("a"), shown!!.keys.toList())
        assertEquals(listOf("next"), shown.getValue("a").map { it.line })
    }

    // Lines per favorite

    @Test
    fun aFavoriteWithLinesShowsOnlyThoseBuses() {
        fun bus(line: String) =
            BusArrival(line = line, destination = "Terminal", arrivalTime = at(2026, 9, 28, 12, 30), vehicleId = line)
        val stop = favorite("a", 3.45, -76.53).copy(lines = listOf("A47"))

        assertEquals(listOf("A47", "A47"), stop.pick(listOf(bus("A47"), bus("T52"), bus("A47"))).map { it.line })
        assertEquals(1, favorite("b", 3.45, -76.53).pick(listOf(bus("T52"))).size)
    }

    // The fix: every error ends a refresh

    @Test
    fun anErrorOfAnyTypeEndsTheRefresh() = test {
        val bus = BusArrival(line = "A47", destination = "X", arrivalTime = at(2026, 9, 28, 12, 0), vehicleId = "1")
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a", arrivals = listOf(bus))),
        )
        val stops = ready(repository)
        val before = stops.state.arrivals

        // A wrongly typed field in the answer throws past ApiException
        repository.failure = JsonTypeException("type 'String' is not a subtype of type 'int'")
        stops.refreshArrivals(force = true).join()

        assertFalse(stops.state.isRefreshing)
        assertNotNull(stops.state.error)
        assertTrue(stops.state.error is ApiException)
        // A failed request says nothing about the stops: what was shown stays
        assertEquals(before, stops.state.arrivals)

        // And the next refresh goes ahead
        repository.failure = null
        repository.nearbyCalls = 0
        stops.refreshArrivals(force = true).join()
        assertEquals(1, repository.nearbyCalls)
        assertNull(stops.state.error)
    }

    @Test
    fun aFavoriteThatCannotBeSavedStillEndsTheRefresh() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(NearbyStop(id = "a", name = "Renamed", distanceMeters = 5.0)),
        )
        repository.saveFailure = IllegalStateException("database is locked")
        val stops = ready(repository)

        stops.refreshArrivals(force = true).join()

        assertFalse(stops.state.isRefreshing)
        assertNotNull(stops.state.error)
        // The arrivals still show; the name waits until it can be saved
        assertEquals(emptyList<BusArrival>(), stops.state.arrivals["a"])
        assertEquals("Stop a", stops.state.favorites.single().name)
    }

    @Test
    fun aRefreshCutShortIsOverToo() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a")),
        )
        val stops = ready(repository)

        repository.answer = { awaitCancellation() }
        val refresh = stops.refreshArrivals(force = true)
        assertTrue(stops.state.isRefreshing)
        refresh.cancel()
        refresh.join()

        assertFalse(stops.state.isRefreshing)
    }

    @Test
    fun refreshesOnThePaceTheNextBusSetsWhileWatched() = test {
        val start = System.currentTimeMillis()
        val soon = BusArrival(
            line = "A47",
            destination = "X",
            arrivalTime = start + 3.minutes.inWholeMilliseconds,
            vehicleId = "1",
        )
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.4516, -76.5320)),
            nearby = listOf(nearbyStop("a", arrivals = listOf(soon))),
        )
        // The clock the gap is measured on runs with the test's
        val stops = viewModel(repository, now = { start + testScheduler.currentTime })
        stops.startAutoRefresh(HOME, homeOnly = true)

        repository.nearbyCalls = 0
        // A bus three minutes out: every 30 s, past the 15 s gap
        advanceTimeBy(30.seconds + 1.seconds)
        assertEquals(1, repository.nearbyCalls)

        // Out of sight: no more
        stops.stopAutoRefresh(HOME)
        advanceTimeBy(5.minutes)
        assertEquals(1, repository.nearbyCalls)
    }

    private companion object {
        val HOME = Any()
        val STOPS_SCREEN = Any()

        /** Epoch milliseconds of a local time, as Dart's DateTime(...) */
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day, hour, minute)
            }.timeInMillis
    }
}
