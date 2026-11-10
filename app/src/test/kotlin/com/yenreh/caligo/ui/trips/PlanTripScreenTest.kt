package com.yenreh.caligo.ui.trips

import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.entities.WalkLeg
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * plan_trip_screen.dart over a planner fed by fakes and fixture tiles:
 * the loading, failed, hint, planning, empty and options states, each
 * option's legs, first bus and steps, the map of the one open, the line
 * choice, the live times and their refresh, the places and their swap,
 * the locate button and Google Maps
 */
@RunWith(AndroidJUnit4::class)
class PlanTripScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val trips = TripFixtures()
    private val chosen = mutableListOf<Boolean>()
    private val opened = mutableListOf<String>()

    @After
    fun clear() = trips.clear()

    private fun show(model: PlanTripViewModel) {
        compose.setContent {
            val links = remember {
                object : UriHandler {
                    override fun openUri(uri: String) {
                        opened.add(uri)
                    }
                }
            }
            CompositionLocalProvider(LocalUriHandler provides links) {
                CaliGoTheme {
                    PlanTripScreen(
                        model,
                        tiles = remember { FixtureTiles() },
                        darkTiles = false,
                        carto = true,
                        onBack = {},
                        onChoose = { chosen.add(it) },
                        shown = true,
                        now = { trips.clock },
                        fadeIn = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private val PlanTripViewModel.option get() = (screen.value.results as PlanResults.Options).rows.first().option

    /** As the screen writes [epochMillis] on this device, in English */
    private fun clock(epochMillis: Long) =
        clockText(epochMillis, hours24 = DateFormat.is24HourFormat(compose.activity), spanish = false)

    // The states

    @Test
    fun tellsHowManyRoutesAreInThenPlans() {
        val gate = CompletableDeferred<Unit>()
        val repository = trips.repository().apply { allRoutesAnswer = { gate.await() } }
        val model = trips.planner(repository)
        show(model)

        compose.onNodeWithText("Loading MIO routes (1/1)").assertExists()
        compose.onNodeWithTag("loading_routes").assertExists()

        gate.complete(Unit)
        waitFor(hasTestTag("option_0"))
        assertFalse(exists(hasTestTag("loading_routes")))
    }

    @Test
    fun routesThatFailToLoadCanBeAskedForAgain() {
        // Not a service failure, so nothing tries again by itself
        val repository = trips.repository().apply { allRoutesFailure = IllegalStateException("Bad state") }
        val model = trips.planner(repository)
        show(model)

        compose.onNodeWithText("Could not load arrivals").assertExists()

        repository.allRoutesFailure = null
        compose.onNodeWithText("Retry").performClick()

        waitFor(hasTestTag("option_0"))
        assertEquals(2, repository.allRoutesCalls)
    }

    @Test
    fun hintsUntilBothPlacesAreChosen() {
        val model = trips.planner(location = trips.location(lastKnown = null), destination = null)
        show(model)

        compose.onNodeWithText("Choose where you leave from and where you are going to see how to get there.").assertExists()
        compose.onNodeWithText("Where from?").assertExists()
        compose.onNodeWithText("Where to?").assertExists()
        // Nothing to swap, time or look up yet
        compose.onNodeWithContentDescription("Swap origin and destination").assertIsNotEnabled()
        compose.onNodeWithTag("google_maps").assertIsNotEnabled()
        assertFalse(exists(hasContentDescription("Update times")))

        compose.onNodeWithTag("origin_field").performClick()
        compose.onNodeWithTag("destination_field").performClick()
        assertEquals(listOf(true, false), chosen)
    }

    @Test
    fun spinsWhilePlanningWithNothingToShowYet() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repository = trips.repository().apply { learnedAnswer = { if (++calls == 1) gate.await() } }
        val model = trips.planner(repository)
        show(model)

        compose.onNodeWithTag("planning").assertExists()

        gate.complete(Unit)
        waitFor(hasTestTag("option_0"))
        assertFalse(exists(hasTestTag("planning")))
    }

    @Test
    fun saysSoWhenNoTripIsFound() {
        // Far north of every stop, and too far to walk
        val model = trips.planner(destination = trips.place(0.0, 100.0, "Far"))
        show(model)

        compose.onNodeWithText("No MIO trip found between these two places at this time.").assertExists()
    }

    @Test
    fun replansUnderAThinBarKeepingTheOptionsOnShow() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        // The first plan and its arrivals each read what is learned: the third read is the new plan's
        val repository = trips.repository().apply { learnedAnswer = { if (++calls == 3) gate.await() } }
        val model = trips.planner(repository)
        show(model)
        waitFor(hasTestTag("option_0"))

        compose.runOnUiThread { model.choose(origin = false, place = trips.place(6.0, 0.5, "Nearer")) }
        compose.waitForIdle()

        compose.onNodeWithTag("replanning").assertExists()
        compose.onNodeWithTag("option_0").assertExists()

        gate.complete(Unit)
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasTestTag("replanning")) }
        compose.onNodeWithText("Nearer").assertExists()
    }

    // The options

    @Test
    fun eachOptionTellsItsTimeLegsFirstBusAndWalk() {
        val model = trips.planner()
        show(model)
        val option = model.option

        compose.onNodeWithText("${minutes(option.duration)} min").assertExists()
        compose.onNodeWithText("Arrive ~${clock(option.arrivesAt!!)}").assertExists()
        compose.onNodeWithText("T1").assertExists()
        // No bus on its way: the usual wait, where it is boarded
        compose.onNodeWithText("No live bus · about ${minutes(option.firstRide!!.wait)} min wait · a 0").assertExists()
        compose.onNodeWithText("Direct · ${roundTo10(option.walkMeters)} m on foot").assertExists()
        compose.onNodeWithText(
            "Live times from MIO. Anything marked ~ is approximate: no bus on its way confirms it, so the line's usual figures are used.",
        ).assertExists()
        assertFalse(exists(hasTestTag("steps")))
        assertFalse(exists(hasTestTag("map")))
    }

    @Test
    fun aLiveBusGivesTheTimeItLeavesAndWhenTheTripArrives() {
        val repository = trips.repository().apply {
            tripArrivals = { trips.arrivals("a0" to listOf(trips.bus("T1", "v1", 8)), "a12" to listOf(trips.bus("T1", "v1", 30))) }
        }
        val model = trips.planner(repository)
        show(model)
        val option = model.option
        assertTrue(option.isLive)

        compose.onNodeWithText("Arrive ${clock(option.arrivesAt!!)}").assertExists()
        compose.onNodeWithText("T1 at ${clock(trips.clock + 8.minutes.inWholeMilliseconds)} (in 8 min) · a 0").assertExists()
    }

    @Test
    fun aBusDueNowIsArriving() {
        // Starting at the stop itself, a bus there now can be caught
        val repository = trips.repository().apply {
            tripArrivals = { trips.arrivals("a0" to listOf(trips.bus("T1", "v1", 0)), "a12" to listOf(trips.bus("T1", "v1", 22))) }
        }
        show(trips.planner(repository, location = trips.location(lastKnown = trips.point(0.0, 0.0))))

        compose.onNodeWithText("T1 arriving · a 0").assertExists()
    }

    @Test
    fun aBusGoneFromTheAnswersWhenDueIsAtTheStop() {
        // Starting at the stop itself; the next answer no longer lists the bus due in a minute
        var calls = 0
        val repository = trips.repository().apply {
            tripArrivals = {
                if (++calls == 1) {
                    trips.arrivals("a0" to listOf(trips.bus("T1", "v1", 1), trips.bus("T1", "v2", 12)), "a12" to listOf(trips.bus("T1", "v1", 23)))
                } else {
                    trips.arrivals("a0" to listOf(trips.bus("T1", "v2", 12)), "a12" to listOf(trips.bus("T1", "v1", 23)))
                }
            }
        }
        val model = trips.planner(repository, location = trips.location(lastKnown = trips.point(0.0, 0.0)))
        show(model)
        trips.clock += 30_000
        compose.onNodeWithContentDescription("Update times").performClick()

        waitFor(hasText("T1 at the stop · a 0"))
        assertEquals(Duration.ZERO, model.option.firstRide!!.wait)
    }

    @Test
    fun saysSoWhenSomeArrivalsCouldNotBeAskedFor() {
        val repository = trips.repository().apply { tripArrivals = { LiveArrivals(trips.clock, emptyList(), failed = true) } }
        show(trips.planner(repository))

        compose.onNodeWithText("No live MIO data for some stretches: those times are approximate.").assertExists()
    }

    @Test
    fun anOptionOpensToItsStepsAndItsMapAndClosesAgain() {
        val model = trips.planner()
        show(model)
        val option = model.option
        val walk = option.legs.first() as WalkLeg
        val ride = option.firstRide!!

        compose.onNodeWithText("${minutes(option.duration)} min").performClick()

        compose.onNodeWithTag("option_0").assertIsSelected()
        compose.onNodeWithText("Walk ${minutes(walk.duration)} min to a 0").assertExists()
        compose.onNodeWithText("Take T1 towards ${ride.towards}").assertExists()
        compose.onNodeWithText("Wait ~${minutes(ride.wait)} min · 12 stops · ~${minutes(ride.duration)} min").assertExists()
        compose.onNodeWithText("Get off at a 12").assertExists()
        compose.onNodeWithText("Walk ${minutes(option.legs.last().total)} min to your destination").assertExists()
        // The map, framing it whole, with both places and where the bus is boarded and left
        compose.onNodeWithTag("map").assertExists()
        waitFor(hasTestTag("map_stop_a12"))
        listOf("map_from", "map_to", "map_stop_a0").forEach { assertTrue(it, exists(hasTestTag(it))) }
        compose.onNodeWithContentDescription("a 0", useUnmergedTree = true).assertExists()

        compose.onNodeWithText("${minutes(option.duration)} min").performClick()

        compose.onNodeWithTag("option_0").assertIsNotSelected()
        assertFalse(exists(hasTestTag("steps")))
        assertFalse(exists(hasTestTag("map")))
    }

    @Test
    fun aRideMadeByTwoLinesShowsEitherOne() {
        val route = trips.line("a", 0..12)
        val repository = trips.repository().apply {
            routes = mapOf("T1" to route, "T2" to route.map { it.copy(direction = 1) })
            tripArrivals = { trips.arrivals("a0" to listOf(trips.bus("T1", "v1", 12), trips.bus("T2", "v2", 6))) }
        }
        val model = trips.planner(repository)
        show(model)
        compose.onNodeWithText("T1 / T2").assertExists()
        compose.onNodeWithTag("option_0").performClick()

        // The one shown is lit, the other a tap away
        compose.onNodeWithTag("line_choice_T2").assertIsSelected()
        compose.onNodeWithTag("line_choice_T1").assertIsNotSelected()
        compose.onNodeWithText("Any of these lines will do; tap one to see its route").assertExists()
        compose.onNodeWithText("Take T2 towards ${model.option.firstRide!!.towards}").assertExists()

        compose.onNodeWithTag("line_choice_T1").performScrollTo().performClick()

        compose.onNodeWithTag("line_choice_T1").assertIsSelected()
        compose.onNodeWithText("Take T1 towards ${model.option.firstRide!!.towards}").assertExists()
        assertEquals("T1", model.screen.value.open!!.firstRide!!.line)
    }

    // The bar

    @Test
    fun theRefreshButtonAsksAgainReusingNoAreaAndSpinsMeanwhile() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repository = trips.repository().apply {
            tripArrivals = {
                if (++calls == 2) gate.await()
                LiveArrivals.NONE
            }
        }
        val model = trips.planner(repository)
        show(model)
        compose.onNodeWithContentDescription("Update times").assertIsEnabled()

        compose.onNodeWithContentDescription("Update times").performClick()

        compose.onNodeWithTag("fetching_live", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Update times").assertIsNotEnabled()
        assertEquals(listOf(1.minutes, Duration.ZERO), repository.tripAsked.map { it.second })

        gate.complete(Unit)
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasTestTag("fetching_live")) }
        compose.onNodeWithContentDescription("Update times").assertIsEnabled()
    }

    @Test
    fun googleMapsOpensTheSameSearch() {
        val model = trips.planner()
        show(model)

        compose.onNodeWithTag("google_maps").performClick()

        assertEquals(listOf(googleMapsDirections(model.screen.value.from!!, trips.to)), opened)
    }

    // The places

    @Test
    fun showsBothPlacesAndSwapsThem() {
        show(trips.planner())
        compose.onNodeWithText("My location").assertExists()
        compose.onNodeWithText("Destination").assertExists()

        compose.onNodeWithContentDescription("Swap origin and destination").performClick()

        compose.onNodeWithTag("origin_field").assert(hasText("Destination"))
        compose.onNodeWithTag("destination_field").assert(hasText("My location"))
        // The line only runs east
        compose.onNodeWithText("No MIO trip found between these two places at this time.").assertExists()
    }

    @Test
    fun theLocateButtonSpinsThenSaysSoWhenThereIsNoFix() {
        val gate = CompletableDeferred<Unit>()
        val location = trips.location().apply { waiting = { gate.await() } }
        // A quiet fix on opening waits too: let it go first
        val model = trips.planner(location = location)
        show(model)
        compose.onNodeWithTag("locating", useUnmergedTree = true).assertExists()
        gate.complete(Unit)
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(hasTestTag("locating")) }

        location.waiting = null
        compose.onNodeWithContentDescription("Update my location").performClick()

        compose.waitUntil(WAIT_TIMEOUT_MS) { exists(hasText("Could not get your location")) }
        assertEquals(2, location.currentAsked.size)
    }

    @Test
    fun theLocateButtonTakesAFreshFix() {
        val location = trips.location()
        val model = trips.planner(location = location)
        show(model)
        compose.runOnUiThread { model.choose(origin = true, place = trips.place(1.0, 0.5, "Home")) }
        compose.onNodeWithText("Home").assertExists()

        location.current = trips.point(0.0, 0.5)
        compose.onNodeWithContentDescription("Update my location").performClick()

        compose.onNodeWithText("My location").assertExists()
        assertTrue(model.screen.value.from!!.isDevice)
    }

    @Test
    fun aPlacePickedIsTakenAsTheScreenShowsAgain() {
        val model = trips.planner(destination = null)
        val picked = PickedPlaces(place = PickedPlace(origin = false, place = trips.to))
        compose.setContent {
            CaliGoTheme {
                PlanTripRoute(
                    settings = Settings(isLoading = false),
                    tiles = remember { FixtureTiles() },
                    picked = picked,
                    onBack = {},
                    onPick = { _, _ -> },
                    viewModel = model,
                )
            }
        }
        compose.waitForIdle()

        assertEquals(trips.to, model.screen.value.to)
        assertNull(picked.place)
        waitFor(hasTestTag("option_0"))
    }

    @Test
    fun thePickerOpensNearTheEndItChooses() {
        val model = trips.planner()
        val asked = mutableListOf<Pair<Boolean, TripPlace?>>()
        compose.setContent {
            CaliGoTheme {
                PlanTripRoute(
                    settings = Settings(isLoading = false),
                    tiles = remember { FixtureTiles() },
                    picked = remember { PickedPlaces() },
                    onBack = {},
                    onPick = { origin, near -> asked.add(origin to near) },
                    viewModel = model,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("origin_field").performClick()
        compose.onNodeWithTag("destination_field").performClick()

        assertEquals(listOf(true to model.screen.value.from, false to trips.to), asked)
    }

    @Test
    fun theArrivalsAreAskedForOnlyWhileShown() {
        val repository = trips.repository()
        val model = trips.planner(repository)
        var shown by mutableStateOf(true)
        compose.setContent {
            CaliGoTheme {
                PlanTripScreen(
                    model,
                    tiles = remember { FixtureTiles() },
                    darkTiles = false,
                    carto = true,
                    onBack = {},
                    onChoose = {},
                    shown = shown,
                    now = { trips.clock },
                    fadeIn = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("option_0").performClick()
        val asked = repository.tripAsked.size

        // Covered while the open trip's arrivals age: nothing is asked for
        shown = false
        compose.waitForIdle()
        trips.clock += 3.minutes.inWholeMilliseconds
        // Back in view: at once
        shown = true
        compose.waitForIdle()

        assertEquals(asked + 1, repository.tripAsked.size)
    }

}
