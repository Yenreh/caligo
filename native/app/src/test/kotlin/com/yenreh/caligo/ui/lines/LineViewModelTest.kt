@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.lines

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.TransitLine
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.Loaded
import kotlin.time.Duration.Companion.hours
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The line screen's state, lineStopsProvider and lineBusesProvider: the
 * route and its directions, the stop picked, and the buses asked for
 * every 30 s while the screen is in view and not at all while it is not.
 * On the test's main dispatcher, whose clock moves only when told.
 */
class LineViewModelTest {
    private val store = ViewModelStore()
    private val main = UnconfinedTestDispatcher()

    @Before
    fun main() = Dispatchers.setMain(main)

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** runTest on the main dispatcher's clock, then the view models cleared, so no round keeps it running */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(main) {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    private fun stop(id: String, direction: Int, sequence: Int, latitude: Double = 3.45) =
        LineStop(id, "Stop $id", latitude, -76.53, direction, sequence)

    private val route = listOf(
        stop("a", 0, 1, 3.40),
        stop("b", 0, 2, 3.42),
        stop("c", 0, 3, 3.44),
        stop("x", 1, 1, 3.44),
        stop("y", 1, 2, 3.40),
    )

    private fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf("A47" to route)
        buses = mapOf("A47" to listOf(LineBus("1", 3.41, -76.53, direction = 0)))
    }

    private fun viewModel(repository: FakeStopsRepository): LineViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { LineViewModel("A47", repository) } },
    )[LineViewModel::class]

    // The route

    @Test
    fun loadsTheRouteOnceAndShowsItsFirstDirection() = test {
        val repository = repository()
        val model = viewModel(repository)

        assertEquals(Loaded.Ready(route), model.screen.value.stops)
        assertEquals(listOf("A47"), repository.routesAsked)

        val shown = ShownRoute(route, model.screen.value.chosenDirection)
        assertEquals(listOf(0, 1), shown.directions)
        assertEquals(0, shown.direction)
        assertEquals(listOf("a", "b", "c"), shown.stops.map { it.stopId })
        // Each direction is named by where it ends
        assertEquals(listOf("Stop c", "Stop y"), shown.ends)
    }

    @Test
    fun aRouteThatFailsCanBeAskedForAgain() = test {
        val repository = repository().apply { routeFailure = NetworkApiException("Connection error") }
        val model = viewModel(repository)
        assertTrue(model.screen.value.stops is Loaded.Failed)

        repository.routeFailure = null
        model.reloadStops()

        assertEquals(Loaded.Ready(route), model.screen.value.stops)
    }

    @Test
    fun aDirectionTheRouteLacksFallsBackToItsFirst() {
        val shown = ShownRoute(listOf(stop("only", 1, 1)), chosen = 0)

        assertEquals(1, shown.direction)
        assertEquals(listOf(1), shown.directions)
    }

    @Test
    fun choosingADirectionLetsThePickedStopGoAndFramesIt() = test {
        val model = viewModel(repository())
        val map = model.mapFor(DpSize(400.dp, 300.dp))
        model.open(route[1])
        assertEquals("b", model.screen.value.selectedStopId)

        model.chooseDirection(1)

        assertEquals(1, model.screen.value.chosenDirection)
        assertNull(model.screen.value.selectedStopId)
        assertEquals(listOf("x", "y"), ShownRoute(route, 1).stops.map { it.stopId })
        // Framed on its two stops, from 3.40 to 3.44
        assertEquals(3.42, map.camera.center.latitude, 1e-3)
    }

    @Test
    fun openingAStopBringsTheMapToItAtStreetLevelAtLeast() = test {
        val model = viewModel(repository())
        val map = model.mapFor(DpSize(400.dp, 300.dp))
        assertTrue(map.camera.zoom < 16)
        assertSame(map, model.mapFor(DpSize(800.dp, 600.dp)))

        model.open(route[2])

        assertEquals(GeoPoint(3.44, -76.53), map.camera.center)
        assertEquals(16.0, map.camera.zoom, 0.0)
        assertFalse(map.rotatable)

        // Deeper already: the zoom stays
        map.move(map.camera.center, 17.5)
        model.open(route[0])
        assertEquals(17.5, map.camera.zoom, 0.0)
    }

    @Test
    fun theMapOpensFramingTheDirectionShown() = test {
        val model = viewModel(repository())

        val map = model.mapFor(DpSize(400.dp, 300.dp))

        assertEquals(3.42, map.camera.center.latitude, 1e-3)
        assertEquals(-76.53, map.camera.center.longitude, 1e-9)
        assertTrue(map.camera.zoom in LineViewModel.MIN_ZOOM..LineViewModel.MAX_ZOOM)
    }

    // The buses

    @Test
    fun theBusesAreAskedForOnlyOnceInViewThenEveryThirtySeconds() = test {
        val repository = repository()
        val model = viewModel(repository)
        advanceTimeBy(5.seconds)
        assertTrue(repository.busesAsked.isEmpty())
        assertTrue(model.screen.value.updatingBuses)

        model.setShown(true)
        assertEquals(1, repository.busesAsked.size)
        assertEquals(Loaded.Ready(repository.buses.getValue("A47")), model.screen.value.buses)
        assertFalse(model.screen.value.updatingBuses)

        advanceTimeBy(29.seconds)
        assertEquals(1, repository.busesAsked.size)
        advanceTimeBy(2.seconds)
        assertEquals(2, repository.busesAsked.size)
        advanceTimeBy(30.seconds)
        assertEquals(3, repository.busesAsked.size)
    }

    @Test
    fun outOfViewTheBusesAreLetGoAndBackInViewAskedForAtOnce() = test {
        val repository = repository()
        val model = viewModel(repository)
        model.setShown(true)
        advanceTimeBy(10.seconds)

        model.setShown(false)
        assertEquals(Loaded.Loading, model.screen.value.buses)
        advanceTimeBy(5.hours)
        assertEquals(1, repository.busesAsked.size)

        model.setShown(true)
        assertEquals(2, repository.busesAsked.size)
        advanceTimeBy(31.seconds)
        assertEquals(3, repository.busesAsked.size)
    }

    @Test
    fun theButtonStartsTheRoundOverPositionsNowTheNextIn30Seconds() = test {
        val repository = repository()
        val model = viewModel(repository)
        model.setShown(true)
        advanceTimeBy(20.seconds)

        model.refreshBuses()
        assertEquals(2, repository.busesAsked.size)

        // Not at the old round's 30 s, but 30 s from the press
        advanceTimeBy(15.seconds)
        assertEquals(2, repository.busesAsked.size)
        advanceTimeBy(16.seconds)
        assertEquals(3, repository.busesAsked.size)
    }

    @Test
    fun theButtonDoesNothingOutOfView() = test {
        val repository = repository()
        val model = viewModel(repository)

        model.refreshBuses()
        advanceTimeBy(1.hours)

        assertTrue(repository.busesAsked.isEmpty())
    }

    @Test
    fun whileTheButtonsAnswerComesTheBusesOnShowStay() = test {
        val repository = repository()
        val model = viewModel(repository)
        model.setShown(true)
        val held = CompletableDeferred<Unit>()
        repository.busAnswer = { held.await() }

        model.refreshBuses()

        assertTrue(model.screen.value.updatingBuses)
        assertEquals(1, model.screen.value.buses.valueOrNull!!.size)
        repository.buses = mapOf("A47" to emptyList())
        held.complete(Unit)
        runCurrent()
        assertFalse(model.screen.value.updatingBuses)
        assertEquals(Loaded.Ready(emptyList<LineBus>()), model.screen.value.buses)
    }

    @Test
    fun aFailedRoundHidesTheBusesAndTheNextOneBringsThemBack() = test {
        val repository = repository()
        val model = viewModel(repository)
        model.setShown(true)

        repository.busFailure = NetworkApiException("Connection error")
        advanceTimeBy(31.seconds)
        assertTrue(model.screen.value.buses is Loaded.Failed)
        assertNull(model.screen.value.buses.valueOrNull)
        assertFalse(model.screen.value.updatingBuses)

        repository.busFailure = null
        advanceTimeBy(30.seconds)
        assertEquals(1, model.screen.value.buses.valueOrNull!!.size)
    }

    @Test
    fun aSlowAnswerDoesNotHoldBackTheNextRequest() = test {
        val repository = repository()
        val model = viewModel(repository)
        val held = CompletableDeferred<Unit>()
        repository.busAnswer = { held.await() }

        model.setShown(true)
        advanceTimeBy(31.seconds)

        // As a periodic timer: the second request went out while the first waits
        assertEquals(2, repository.busesAsked.size)
        held.complete(Unit)
    }

    @Test
    fun clearedTheRoundStops() = test {
        val repository = repository()
        viewModel(repository).setShown(true)

        store.clear()
        advanceTimeBy(5.hours)

        assertEquals(1, repository.busesAsked.size)
    }
}

/**
 * linesProvider and lineHoursProvider: each loaded on first use and kept;
 * the lines asked for again on Retry, the hours never failing and asked
 * again after a failure or a day
 */
class LinesViewModelTest {
    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun clear() = Dispatchers.resetMain()

    @Test
    fun theLinesLoadOnceAndAgainOnlyWhenAskedTo() = runTest {
        val repository = FakeStopsRepository(emptyList(), emptyList()).apply {
            transitLines = listOf(TransitLine(347, "A47"))
            catalogFailure = NetworkApiException("Connection error")
        }
        val model = LinesViewModel(repository)
        assertEquals(Loaded.Loading, model.lines.value)
        assertEquals(0, repository.lineCalls)

        model.watchLines()
        assertTrue(model.lines.value is Loaded.Failed)
        repository.catalogFailure = null
        // A failure is kept too, until asked again
        model.watchLines()
        assertEquals(1, repository.lineCalls)
        assertTrue(model.lines.value is Loaded.Failed)

        model.reloadLines()
        assertEquals(Loaded.Ready(listOf(TransitLine(347, "A47"))), model.lines.value)
        model.watchLines()
        assertEquals(2, repository.lineCalls)
    }

    @Test
    fun theHoursAreAskedAgainAfterAFailureAndOnceADayOld() = runTest {
        var clock = 1_790_000_000_000L
        val hours = mapOf("A47" to LineHours(5.hours, 20.hours))
        val repository = FakeStopsRepository(emptyList(), emptyList()).apply {
            this.hours = hours
            hourFailure = NetworkApiException("Connection error")
        }
        val model = LinesViewModel(repository, now = { clock })

        model.watchHours()
        assertEquals(emptyMap<String, LineHours>(), model.hours.value)

        // The Flutter provider kept the failure for the run
        repository.hourFailure = null
        model.watchHours()
        assertEquals(2, repository.hourCalls)
        assertEquals(hours, model.hours.value)

        // An answer stands for a day, as the cache keeps it
        clock += 23.hours.inWholeMilliseconds
        model.watchHours()
        assertEquals(2, repository.hourCalls)
        clock += 2.hours.inWholeMilliseconds
        model.watchHours()
        assertEquals(3, repository.hourCalls)

        // Past its day, a failure leaves the last hours had
        clock += 25.hours.inWholeMilliseconds
        repository.hourFailure = NetworkApiException("Connection error")
        model.watchHours()
        assertEquals(4, repository.hourCalls)
        assertEquals(hours, model.hours.value)
    }

    @Test
    fun anyFailureOfTheHoursLeavesThemEmpty() = runTest {
        val repository = FakeStopsRepository(emptyList(), emptyList()).apply {
            hourFailure = IllegalStateException("Not a list")
        }
        val model = LinesViewModel(repository)

        model.watchHours()

        assertEquals(emptyMap<String, LineHours>(), model.hours.value)
    }
}
