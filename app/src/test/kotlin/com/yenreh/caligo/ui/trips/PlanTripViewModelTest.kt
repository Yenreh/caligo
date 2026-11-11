@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.trips

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakePlacesRepository
import com.yenreh.caligo.ui.FakeStopsRepository
import java.util.Calendar
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val LAT = 3.45
private const val LON = -76.53

/** About 440 m between stops */
private const val STEP = 0.004

/**
 * The trip planner's state: plan_trip_screen.dart's logic with
 * trip_provider.dart. On the test's main dispatcher, whose clock moves
 * only when told.
 */
class PlanTripViewModelTest {
    private val store = ViewModelStore()
    private val main = UnconfinedTestDispatcher()
    private val work = CoroutineScope(SupervisorJob() + main)
    private var places = FakePlacesRepository()

    /** 2026-09-29 10:00, local time */
    private var clock = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 10, 0)
    }.timeInMillis

    @Before
    fun main() = Dispatchers.setMain(main)

    @After
    fun clear() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(main) {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    private fun place(x: Double, y: Double, name: String = "($x, $y)") = TripPlace(LAT + y * STEP, LON + x * STEP, name)

    private fun line(prefix: String, xs: IntRange, y: Double = 0.0) = xs.mapIndexed { i, x ->
        LineStop("$prefix$x", "$prefix $x", LAT + y * STEP, LON + x * STEP, 0, i + 1)
    }

    private val from = place(0.0, 0.5)
    private val to = place(12.0, 0.5, "Destination")

    private fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf("T1" to line("a", 0..12))
    }

    /** The device last seen at the origin, with no fresh fix to give */
    private fun location(lastKnown: GeoPoint? = GeoPoint(from.latitude, from.longitude)) =
        FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = lastKnown)

    private fun viewModel(
        repository: FakeStopsRepository,
        location: FakeLocationSource = location(),
        destination: TripPlace? = to,
        hours: MutableStateFlow<Map<String, LineHours>?> = MutableStateFlow(emptyMap()),
        planning: CoroutineDispatcher = main,
        loader: TransitNetworkLoader = TransitNetworkLoader(repository, work, building = main),
    ): PlanTripViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer {
                PlanTripViewModel(
                    repository = repository,
                    network = loader,
                    hours = hours,
                    location = location,
                    deviceName = "My location",
                    destination = destination,
                    work = work,
                    now = { clock },
                    planning = planning,
                    savedPlaces = places,
                )
            }
        },
    )[PlanTripViewModel::class]

    private fun bus(line: String, vehicle: String, minutes: Int) =
        BusArrival(line, "End", clock + minutes.minutes.inWholeMilliseconds, vehicle)

    /** An answer fetched now, listing [stops] with their buses */
    private fun arrivals(vararg stops: Pair<String, List<BusArrival>>) = LiveArrivals(
        clock,
        stops.map { (id, buses) -> NearbyStop(id, "Stop $id", 0.0, buses) },
        answeredAt = stops.associate { (id, _) -> id to clock },
    )

    private val PlanTripState.options get() = (results as PlanResults.Options).rows

    // Places and plans

    @Test
    fun startsFromWhereTheDeviceWasLastSeenAndPlansAtOnce() = test {
        val repository = repository()
        val model = viewModel(repository)
        val state = model.screen.value

        assertEquals(TripPlace(from.latitude, from.longitude, "My location", isDevice = true), state.from)
        assertEquals(to, state.to)
        assertFalse(state.planning)
        assertFalse(state.locating)
        assertEquals(listOf("T1"), state.options.single().option.rides.map { it.line })
        // Timed from now by the usual figures: no bus on its way
        assertEquals(clock, state.options.single().option.startsAt)
        assertFalse(state.options.single().option.isLive)
        // Then the buses on their way to where it boards and leaves, reusing a minute
        assertEquals(listOf(listOf("a0", "a12") to 1.minutes), repository.tripAsked.map { (s, age) -> s.map { it.stopId } to age })
        assertFalse(state.fetchingLive)
        assertTrue(state.canRefresh)
    }

    @Test
    fun hintsUntilBothPlacesAreChosen() = test {
        val model = viewModel(repository(), location = location(lastKnown = null), destination = null)
        assertEquals(PlanResults.Hint, model.screen.value.results)
        assertFalse(model.screen.value.canSwap)
        assertFalse(model.screen.value.canRefresh)

        model.choose(origin = false, place = to)
        assertEquals(PlanResults.Hint, model.screen.value.results)
        assertEquals(to, model.pickerNear(origin = true))

        model.choose(origin = true, place = from)
        assertEquals(1, model.screen.value.options.size)
    }

    @Test
    fun tellsHowManyRoutesAreInThenPlansOnceTheyAre() = test {
        val gate = CompletableDeferred<Unit>()
        val repository = repository().apply { allRoutesAnswer = { gate.await() } }
        val model = viewModel(repository)

        assertEquals(PlanResults.LoadingRoutes(done = 1, total = 1), model.screen.value.results)
        assertNull(model.screen.value.plans)

        gate.complete(Unit)
        assertEquals(1, model.screen.value.options.size)
    }

    @Test
    fun routesThatFailToLoadCanBeAskedForAgain() = test {
        val repository = repository().apply { allRoutesFailure = ServerApiException("Server error: 503", 503) }
        val model = viewModel(repository)
        assertEquals(PlanResults.NetworkFailed, model.screen.value.results)

        repository.allRoutesFailure = null
        model.retryNetwork()

        assertEquals(1, model.screen.value.options.size)
    }

    @Test
    fun saysSoWhenNoTripIsFound() = test {
        // Far north of every stop, and too far to walk
        val model = viewModel(repository(), destination = place(0.0, 100.0))

        assertEquals(PlanResults.NoTrip, model.screen.value.results)
    }

    @Test
    fun swappingThePlacesPlansTheOtherWay() = test {
        val model = viewModel(repository())

        model.swap()

        assertEquals(to, model.screen.value.from)
        assertTrue(model.screen.value.to!!.isDevice)
        // The line only runs east
        assertEquals(PlanResults.NoTrip, model.screen.value.results)
    }

    @Test
    fun linesNotRunningAreLeftOutOnceTheHoursCome() = test {
        val repository = repository().apply {
            routes = mapOf("T1" to line("a", 0..12), "A9" to line("c", 0..12, y = 1.0))
        }
        val hours = MutableStateFlow<Map<String, LineHours>?>(null)
        val model = viewModel(repository, hours = hours)
        assertEquals("T1", model.screen.value.options.first().option.firstRide!!.line)

        // T1 runs from one to two in the morning only
        hours.value = mapOf("T1" to LineHours(1.hours, 2.hours), "A9" to LineHours(4.hours, 23.hours))

        val rides = model.screen.value.options.flatMap { it.option.rides }
        assertTrue(rides.isNotEmpty())
        assertTrue(rides.all { it.line == "A9" })
    }

    @Test
    fun aPlanThatFailsEndsTheSpinnerAndSaysSo() = test {
        val repository = repository().apply { learnedAnswer = { error("Disk gone") } }
        val model = viewModel(repository)

        // The Flutter planner spun for good
        assertFalse(model.screen.value.planning)
        assertEquals(PlanTripMessage.COULD_NOT_PLAN, withTimeoutOrNull(1_000) { model.messages.first() })
    }

    @Test
    fun aPlanOvertakenByANewerOneIsDropped() = test {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repository = repository().apply {
            learnedAnswer = { if (++calls == 1) gate.await() }
        }
        val model = viewModel(repository)
        assertTrue(model.screen.value.planning)

        val nearer = place(6.0, 0.5, "Nearer")
        model.choose(origin = false, place = nearer)
        val newer = model.screen.value.plans!!
        assertEquals("a6", newer.single().rides.single().alight.stopId)

        gate.complete(Unit)
        assertSame(newer, model.screen.value.plans)
    }

    @Test
    fun plansOffTheMainThread() = test {
        // The test's main dispatcher runs the view model on this thread
        val mainThread = Thread.currentThread()
        val planning = RecordingDispatcher(Dispatchers.Default)
        val model = viewModel(repository(), planning = planning)

        val state = withContext(Dispatchers.Default) {
            withTimeout(WAIT_TIMEOUT_MS) { model.screen.first { it.plans != null && !it.fetchingLive } }
        }
        assertEquals(1, state.options.size)
        // The plan ran on the planning dispatcher, and never on the main thread
        assertTrue(planning.threads.isNotEmpty())
        assertTrue(planning.threads.none { it === mainThread })
    }

    // The buses on their way

    @Test
    fun timesTheTripAgainstTheBusesOnTheirWayAndLearnsFromThem() = test {
        val repository = repository().apply {
            tripArrivals = { arrivals("a0" to listOf(bus("T1", "v1", 8)), "a12" to listOf(bus("T1", "v1", 30))) }
        }
        val model = viewModel(repository)
        val option = model.screen.value.options.single().option

        assertTrue(option.isLive)
        assertEquals(clock + 8.minutes.inWholeMilliseconds, option.firstRide!!.departsAt)
        assertEquals(22.minutes, option.firstRide!!.duration)
        // 22 minutes against the route's estimate
        val reading = repository.observedRides.single().getValue("T1")
        assertEquals(22.minutes.inWholeSeconds.toDouble() / model.screen.value.plans!!.single().firstRide!!.duration.inWholeSeconds, reading, 1e-9)
    }

    @Test
    fun saysSoWhenSomeArrivalsCouldNotBeAskedFor() = test {
        val repository = repository().apply { tripArrivals = { LiveArrivals(clock, emptyList(), failed = true) } }
        val model = viewModel(repository)

        assertTrue((model.screen.value.results as PlanResults.Options).liveFailed)
    }

    @Test
    fun theRefreshButtonReusesNoArea() = test {
        val repository = repository()
        val model = viewModel(repository)

        model.refreshLive(force = true)

        assertEquals(listOf(1.minutes, Duration.ZERO), repository.tripAsked.map { it.second })
    }

    @Test
    fun anOpenTripAsksAgainForItsOwnStopsOnlyOnceTheyAgeAndOnlyInView() = test {
        val repository = repository().apply {
            routes = mapOf("T1" to line("a", 0..12), "A9" to line("c", 0..12, y = 1.0))
            tripArrivals = { arrivals() }
        }
        val model = viewModel(repository)
        val first = model.screen.value.order.first()
        val asked = repository.tripAsked.size
        model.toggle(first)
        assertEquals(first, model.screen.value.expanded)
        assertSame(model.screen.value.timed[first], model.screen.value.open)

        // Out of view, the ticks only time it again
        clock += 3.minutes.inWholeMilliseconds
        advanceTimeBy(20.seconds + 1.seconds)
        assertEquals(asked, repository.tripAsked.size)
        assertEquals(clock, model.screen.value.open!!.startsAt)

        // Back in view with arrivals three minutes old: at once, the open trip's stops only
        model.setShown(true)
        assertEquals(asked + 1, repository.tripAsked.size)
        val open = model.screen.value.plans!![first]
        assertEquals(
            open.rides.flatMap { listOf(it.board.stopId, it.alight.stopId) },
            repository.tripAsked.last().first.map { it.stopId },
        )

        // Fresh now: the next tick only times it again
        advanceTimeBy(20.seconds + 1.seconds)
        assertEquals(asked + 1, repository.tripAsked.size)

        // Closed, nothing is asked for however old
        model.toggle(first)
        assertNull(model.screen.value.expanded)
        clock += 10.minutes.inWholeMilliseconds
        advanceTimeBy(20.seconds)
        assertEquals(asked + 1, repository.tripAsked.size)
    }

    @Test
    fun arrivalsWantedWhileOthersAreOnTheirWayAreAskedForOnceTheyLand() = test {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repository = repository().apply {
            tripArrivals = {
                if (++calls == 1) gate.await()
                arrivals()
            }
        }
        val model = viewModel(repository)
        assertTrue(model.screen.value.fetchingLive)

        model.refreshLive()
        model.refreshLive()
        assertEquals(1, repository.tripAsked.size)

        gate.complete(Unit)
        assertEquals(2, repository.tripAsked.size)
        assertFalse(model.screen.value.fetchingLive)
    }

    @Test
    fun aLineChosenForARideKeepsItsPlaceInTheTiming() = test {
        val route = line("a", 0..12)
        val repository = repository().apply {
            routes = mapOf("T1" to route, "T2" to route.map { it.copy(direction = 1) })
            tripArrivals = { arrivals("a0" to listOf(bus("T1", "v1", 12), bus("T2", "v2", 6))) }
        }
        val model = viewModel(repository)
        val row = model.screen.value.options.single()
        assertEquals("T2", row.option.firstRide!!.line)
        assertEquals(listOf("T1", "T2"), row.option.firstRide!!.lines.sorted())

        model.chooseLine(row.index, 0, "T1")

        val chosen = model.screen.value.timed.getValue(row.index).firstRide!!
        assertEquals("T1", chosen.line)
        assertEquals(clock + 12.minutes.inWholeMilliseconds, chosen.departsAt)
    }

    // The device

    @Test
    fun aQuietFixOnlyMovesAnOriginThatIsTheDeviceAndHasMoved() = test {
        val location = location()
        val model = viewModel(repository(), location = location)
        val plans = model.screen.value.plans

        // 50 m on: the same plans
        location.current = GeoPoint(from.latitude + 0.00045, from.longitude)
        model.locate()
        assertSame(plans, model.screen.value.plans)

        // 220 m on: from there
        location.current = GeoPoint(from.latitude + 0.002, from.longitude)
        model.locate()
        assertEquals(from.latitude + 0.002, model.screen.value.from!!.latitude, 0.0)
        assertTrue(model.screen.value.from!!.isDevice)

        // A place chosen by hand stays
        model.choose(origin = true, place = from)
        location.current = GeoPoint(from.latitude + 0.01, from.longitude)
        model.locate()
        assertEquals(from, model.screen.value.from)
    }

    @Test
    fun theLocateButtonTakesAnyFixAndSaysSoWhenThereIsNone() = test {
        val location = location()
        val model = viewModel(repository(), location = location)
        model.choose(origin = true, place = from)

        location.current = GeoPoint(from.latitude, from.longitude)
        model.locate(force = true)
        assertTrue(model.screen.value.from!!.isDevice)

        location.current = null
        model.locate(force = true)
        assertEquals(PlanTripMessage.COULD_NOT_LOCATE, withTimeoutOrNull(1_000) { model.messages.first() })
        assertFalse(model.screen.value.locating)
    }

    @Test
    fun aQuietFixThatFailsSaysNothing() = test {
        val model = viewModel(repository(), location = location(lastKnown = null), destination = null)

        model.locate()

        assertNull(withTimeoutOrNull(1_000) { model.messages.first() })
        assertNull(model.screen.value.from)
    }

    @Test
    fun opensGoogleMapsOnTheSameSearch() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=3.45%2C-76.53&destination=3.0%2C-76.5&travelmode=transit",
            googleMapsDirections(TripPlace(3.45, -76.53, "A"), TripPlace(3.0, -76.5, "B")),
        )
    }

    /** Runs on [delegate], keeping the threads it ran on */
    // Saved places

    private val home = FavoritePlace(7, "Casa", PlaceKind.ADDRESS, "Destination", null, to.latitude, to.longitude, null, 0)

    @Test
    fun aQuickPlanGoesToTheSavedPlaceFromTheOriginAsItIs() = test {
        places = FakePlacesRepository(listOf(home))
        val model = viewModel(repository(), destination = null)
        assertEquals(listOf(home), model.screen.value.quickPlans)

        model.planTo(home)

        val state = model.screen.value
        assertEquals(TripPlace(from.latitude, from.longitude, "My location", isDevice = true), state.from)
        assertEquals(TripPlace(to.latitude, to.longitude, "Casa", kind = PlaceKind.ADDRESS), state.to)
        assertEquals(1, state.options.size)
        // The chips go once there is somewhere to go, and its star is filled
        assertTrue(state.quickPlans.isEmpty())
        assertEquals(home, state.savedTo)
    }

    @Test
    fun aQuickPlanKeepsAnOriginChosenByHand() = test {
        places = FakePlacesRepository(listOf(home))
        val model = viewModel(repository(), location = location(lastKnown = null), destination = null)
        model.choose(origin = true, place = from)

        model.planTo(home)

        assertEquals(from, model.screen.value.from)
        assertEquals(1, model.screen.value.options.size)
    }

    @Test
    fun noQuickPlansWithoutSavedPlaces() = test {
        val model = viewModel(repository(), destination = null)
        assertTrue(model.screen.value.quickPlans.isEmpty())
    }

    @Test
    fun aStarSavesThePlaceOnceAndNeverTheDevice() = test {
        val model = viewModel(repository())
        assertNull(model.screen.value.savedTo)

        model.savePlace(origin = false, name = " Work ")
        val saved = model.screen.value.places.single()
        assertEquals("Work", saved.name)
        assertEquals("Destination", saved.label)
        assertEquals(saved, model.screen.value.savedTo)

        // Saved already, and the device: nothing more
        model.savePlace(origin = false, name = "Again")
        model.savePlace(origin = true, name = "Here")
        assertEquals(listOf("Work"), model.screen.value.places.map { it.name })
        assertNull(model.screen.value.savedFrom)
    }

    @Test
    fun aPlaceAFewMetresOffIsTheOneSaved() = test {
        places = FakePlacesRepository(listOf(home))
        val model = viewModel(repository(), destination = to.copy(latitude = to.latitude + 0.0002, name = "Calle 5"))
        assertEquals(home, model.screen.value.savedTo)

        model.choose(origin = false, place = to.copy(latitude = to.latitude + 0.001))
        assertNull(model.screen.value.savedTo)
    }

    @Test
    fun renamingAPlaceRenamesTheFieldShowingIt() = test {
        places = FakePlacesRepository(listOf(home))
        val model = viewModel(repository(), destination = null)
        model.planTo(home)

        model.renamePlace(home.id, "Mi casa")

        assertEquals(listOf("Mi casa"), model.screen.value.places.map { it.name })
        assertEquals("Mi casa", model.screen.value.to?.name)
        // An empty name is no rename
        model.renamePlace(home.id, "  ")
        assertEquals("Mi casa", model.screen.value.places.single().name)
    }

    @Test
    fun deletingAPlaceEmptiesItsStarAndKeepsTheField() = test {
        places = FakePlacesRepository(listOf(home))
        val model = viewModel(repository())
        assertEquals(home, model.screen.value.savedTo)

        model.deletePlace(home.id)

        assertTrue(model.screen.value.places.isEmpty())
        assertNull(model.screen.value.savedTo)
        assertEquals(to, model.screen.value.to)
    }

    @Test
    fun aPlaceThatCannotBeSavedSaysSo() = test {
        places = FakePlacesRepository().apply { failure = IllegalStateException("disk") }
        val model = viewModel(repository())

        model.savePlace(origin = false, name = "Work")

        assertEquals(PlanTripMessage.COULD_NOT_SAVE_PLACE, withTimeoutOrNull(1_000) { model.messages.first() })
        assertTrue(model.screen.value.places.isEmpty())
    }

    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        /** Every thread a block handed to it ran on */
        val threads: MutableList<Thread> = java.util.concurrent.CopyOnWriteArrayList()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                threads.add(Thread.currentThread())
                block.run()
            }
        }
    }
}
