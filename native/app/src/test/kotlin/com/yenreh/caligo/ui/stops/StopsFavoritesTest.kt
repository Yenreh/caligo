@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.json.favoriteFromJson
import com.yenreh.caligo.data.json.toJson
import com.yenreh.caligo.data.location.LocationAccuracy
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.data.location.LocationUnavailableException
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.nearbyStop
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The rest of stops_provider_test.dart, past the home's cases: reorder,
 * lines per favorite, refreshStopArrivals. Then what the Dart tests left
 * untested: adding, removing, renaming, relinking, the home toggle, the
 * closest-first order and its setting, the location states and the
 * lookups the stop screens make.
 */
class StopsFavoritesTest {
    private val store = ViewModelStore()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

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
        location: FakeLocationSource = FakeLocationSource(),
        settings: MutableStateFlow<Settings> = MutableStateFlow(Settings(isLoading = false)),
    ): StopsViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer { StopsViewModel(repository, location = location, settings = settings) }
        },
    )[StopsViewModel::class]

    /** Ready as _readyNotifier leaves it: its own load done, then another */
    private suspend fun ready(
        repository: FakeStopsRepository,
        location: FakeLocationSource = FakeLocationSource(),
        settings: MutableStateFlow<Settings> = MutableStateFlow(Settings(isLoading = false)),
    ) = viewModel(repository, location, settings).also { it.loadFavorites().join() }

    private val StopsViewModel.state get() = stops.value

    private fun ids(stops: StopsViewModel) = stops.state.favorites.map { it.id }

    // StopsNotifier.reorderFavorites

    @Test
    fun movesAStopDownAsTheListReportsItAndSavesTheOrder() = test {
        val repository = FakeStopsRepository(
            favorites = listOf("a", "b", "c").mapIndexed { i, id -> favorite(id, 3.45, -76.53).copy(position = i) },
            nearby = emptyList(),
        )
        val stops = ready(repository, settings = byPosition())
        repository.saved.clear()

        // Dragging 'a' below 'b': a reorderable list reports index 2
        stops.reorderFavorites(0, 2).join()

        assertEquals(listOf("b", "a", "c"), ids(stops))
        assertEquals(listOf(0, 1, 2), stops.state.favorites.map { it.position })
        // 'c' kept its place, so only the two that moved are written
        assertEquals(listOf("b", "a"), repository.saved.map { it.id })
    }

    @Test
    fun theNewOrderShowsBeforeItIsSaved() = test {
        val repository = FakeStopsRepository(
            favorites = listOf("a", "b").mapIndexed { i, id -> favorite(id, 3.45, -76.53).copy(position = i) },
            nearby = emptyList(),
        )
        val stops = ready(repository, settings = byPosition())
        val gate = CompletableDeferred<Unit>()
        repository.saving = { gate.await() }

        val saving = stops.reorderFavorites(1, 0)

        assertEquals(listOf("b", "a"), ids(stops))
        gate.complete(Unit)
        saving.join()
    }

    // Lines per favorite

    @Test
    fun theLinesTravelWithTheBackup() {
        val stop = favorite("a", 3.45, -76.53).copy(lines = listOf("A47", "T52"))

        assertEquals(listOf("A47", "T52"), favoriteFromJson(stop.toJson()).lines)
        // Backups made before the lines existed show every line
        val old = JsonObject(stop.toJson() - "lines")
        assertTrue(favoriteFromJson(old).lines.isEmpty())
    }

    @Test
    fun editingSavesTheNameAndTheLinesTogether() = test {
        val repository = FakeStopsRepository(favorites = listOf(favorite("a", 3.45, -76.53)), nearby = emptyList())
        val stops = ready(repository)
        repository.saved.clear()

        stops.editFavorite("a", customName = " Casa ", lines = listOf("A47")).join()

        val saved = repository.saved.single()
        assertEquals("Casa", saved.customName)
        assertEquals(listOf("A47"), saved.lines)
        assertEquals(listOf("A47"), stops.state.favorites.single().lines)
    }

    @Test
    fun anEmptyLabelGivesTheRealNameBack() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.45, -76.53).copy(customName = "Casa")),
            nearby = emptyList(),
        )
        val stops = ready(repository)

        stops.editFavorite("a", customName = "  ", lines = emptyList()).join()

        assertNull(stops.state.favorites.single().customName)
        assertEquals("Stop a", stops.state.favorites.single().displayName)
    }

    // refreshStopArrivals

    private val bus = BusArrival(line = "A47", destination = "Valle Grande", arrivalTime = 1_790_605_800_000, vehicleId = "1")

    @Test
    fun takesTheNewArrivalsOfTheStopBeingLookedAt() = test {
        val repository = FakeStopsRepository(
            favorites = emptyList(),
            nearby = listOf(nearbyStop("other"), nearbyStop("a", arrivals = listOf(bus))),
        )

        val fresh = refreshStopArrivals(repository, nearbyStop("a"), anchorLatitude = 3.45, anchorLongitude = -76.53)

        assertEquals(1, repository.nearbyCalls)
        assertEquals(listOf(bus), fresh.arrivals)
    }

    @Test
    fun aStopMissingFromTheAnswerHasNoBusComing() = test {
        val repository = FakeStopsRepository(favorites = emptyList(), nearby = listOf(nearbyStop("other")))

        val fresh = refreshStopArrivals(
            repository,
            nearbyStop("a", arrivals = listOf(bus)),
            anchorLatitude = 3.45,
            anchorLongitude = -76.53,
        )

        assertTrue(fresh.arrivals.isEmpty())
    }

    @Test
    fun asksAroundTheStopItselfWhenItsPlaceIsKnown() = test {
        val repository = FakeStopsRepository(favorites = emptyList(), nearby = emptyList())
        val placed = nearbyStop("a").copy(latitude = 3.46, longitude = -76.52)

        refreshStopArrivals(repository, placed, anchorLatitude = 3.45, anchorLongitude = -76.53)
        refreshStopArrivals(repository, nearbyStop("b"), anchorLatitude = 3.45, anchorLongitude = -76.53)

        assertEquals(listOf(GeoPoint(3.46, -76.52), GeoPoint(3.45, -76.53)), repository.askedAround)
    }

    // Adding, removing, renaming, relinking, the home toggle

    @Test
    fun aStopIsSavedLastWithItsOwnLabelAndLoadedWithItsArrivals() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.45, -76.53)),
            nearby = listOf(nearbyStop("a"), nearbyStop("b", arrivals = listOf(bus))),
        )
        val stops = ready(repository, settings = byPosition())
        repository.nearbyCalls = 0

        val saved = stops.addFavorite(
            id = "b",
            stopId = "b",
            name = "Stop b",
            anchorLatitude = 3.47,
            anchorLongitude = -76.52,
            customName = " Trabajo ",
        ).await()

        assertTrue(saved)
        val added = repository.added.single()
        assertEquals(1, added.position)
        assertEquals("Trabajo", added.customName)
        assertEquals(GeoPoint(3.47, -76.52), GeoPoint(added.anchorLatitude, added.anchorLongitude))
        // Loaded again, and the new stop's buses asked for before it ends
        assertEquals(listOf("a", "b"), ids(stops))
        assertTrue(repository.nearbyCalls > 0)
        assertEquals(listOf(bus), stops.state.arrivals["b"])
    }

    @Test
    fun anAreaHasNoStopAndABlankLabelIsNone() = test {
        val repository = FakeStopsRepository(favorites = emptyList(), nearby = emptyList())
        val stops = ready(repository)

        stops.addFavorite(
            id = "station-12",
            name = "Estación Universidades",
            anchorLatitude = 3.37,
            anchorLongitude = -76.53,
            customName = "   ",
        ).await()

        val added = repository.added.single()
        assertTrue(added.isArea)
        assertNull(added.customName)
        assertEquals(0, added.position)
    }

    @Test
    fun anIdAlreadySavedIsNotSavedTwice() = test {
        val repository = FakeStopsRepository(favorites = listOf(favorite("station-12", 3.37, -76.53)), nearby = emptyList())
        val stops = ready(repository)

        val saved = stops.addFavorite(
            id = "station-12",
            name = "Estación Universidades",
            anchorLatitude = 3.37,
            anchorLongitude = -76.53,
        ).await()

        assertFalse(saved)
        assertEquals(listOf("station-12"), ids(stops))
    }

    @Test
    fun removingDropsTheStopAndItsArrivals() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.45, -76.53), favorite("b", 3.47, -76.53)),
            nearby = listOf(nearbyStop("a", arrivals = listOf(bus)), nearbyStop("b")),
        )
        val stops = ready(repository, settings = byPosition())
        assertTrue("a" in stops.state.arrivals)

        stops.removeFavorite("a").join()

        assertEquals(listOf("a"), repository.removed)
        assertEquals(listOf("b"), ids(stops))
        assertFalse("a" in stops.state.arrivals)
    }

    @Test
    fun aSaveThatFailsLeavesTheStopsAsTheyWere() = test {
        val repository = FakeStopsRepository(
            favorites = listOf(favorite("a", 3.45, -76.53)),
            nearby = emptyList(),
        )
        val stops = ready(repository)
        repository.saveFailure = IllegalStateException("database is locked")

        stops.removeFavorite("a").join()
        stops.setShowOnHome("a", false).join()
        stops.editFavorite("a", customName = "Casa", lines = emptyList()).join()

        val stop = stops.state.favorites.single()
        assertTrue(stop.showOnHome)
        assertNull(stop.customName)
    }

    @Test
    fun aStopCanBeKeptOffTheHomeAndBroughtBack() = test {
        val repository = FakeStopsRepository(favorites = listOf(favorite("a", 3.45, -76.53)), nearby = emptyList())
        val stops = ready(repository)
        repository.saved.clear()

        stops.setShowOnHome("a", false).join()
        assertFalse(stops.state.favorites.single().showOnHome)
        // Already off: nothing to write
        stops.setShowOnHome("a", false).join()
        stops.setShowOnHome("a", true).join()

        assertEquals(listOf(false, true), repository.saved.map { it.showOnHome })
        assertTrue(stops.state.favorites.single().showOnHome)
    }

    @Test
    fun relinkingPointsAtTheNewStopKeepingTheLabelAndThePlace() = test {
        val gone = favorite("a", 3.45, -76.53).copy(customName = "Casa", missingCount = 3)
        val repository = FakeStopsRepository(favorites = listOf(gone), nearby = emptyList())
        val stops = ready(repository)
        repository.saved.clear()

        stops.relinkFavorite("a", NearbyStop(id = "z", name = "Stop z", distanceMeters = 40.0)).join()

        val relinked = repository.saved.first()
        assertEquals("a", relinked.id)
        assertEquals("z", relinked.stopId)
        assertEquals("Stop z", relinked.name)
        assertEquals("Casa", relinked.customName)
        assertEquals(0, relinked.missingCount)
        assertEquals(GeoPoint(3.45, -76.53), GeoPoint(relinked.anchorLatitude, relinked.anchorLongitude))
    }

    // Closest first

    /** a is saved first but lies furthest from the device */
    private fun spread() = FakeStopsRepository(
        favorites = listOf(
            favorite("a", 3.4700, -76.5320).copy(position = 0),
            favorite("b", 3.4600, -76.5320).copy(position = 1),
            favorite("c", 3.4520, -76.5320).copy(position = 2),
        ),
        nearby = emptyList(),
    )

    private val here = GeoPoint(3.4516, -76.5320)

    private fun byPosition() = MutableStateFlow(Settings(isLoading = false, sortStopsByProximity = false))

    @Test
    fun theClosestStopsComeFirstWhenTheLocationIsAllowed() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = here)

        val stops = ready(spread(), location)

        assertEquals(listOf("c", "b", "a"), ids(stops))
        // The last fix was enough
        assertTrue(location.currentAsked.isEmpty())
    }

    @Test
    fun withoutTheLocationTheSavedOrderStandsAndNoPromptShows() = test {
        val location = FakeLocationSource(permission = LocationPermission.DENIED, lastKnown = here)

        val stops = ready(spread(), location)

        assertEquals(listOf("a", "b", "c"), ids(stops))
        assertEquals(0, location.requests)
    }

    @Test
    fun noLastFixAsksForARoughOneWithinEightSeconds() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = here)

        val stops = ready(spread(), location)

        assertEquals(listOf("c", "b", "a"), ids(stops))
        assertEquals(LocationAccuracy.LOW to 8.seconds, location.currentAsked.first())
    }

    @Test
    fun noFixAtAllKeepsTheSavedOrder() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED)

        val stops = ready(spread(), location)

        assertEquals(listOf("a", "b", "c"), ids(stops))
    }

    @Test
    fun theSavedOrderWhenTheSettingIsOff() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = here)

        val stops = ready(spread(), location, byPosition())

        assertEquals(listOf("a", "b", "c"), ids(stops))
    }

    @Test
    fun reordersInPlaceWhenTheSettingFlips() = test {
        val repository = spread()
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = here)
        val settings = byPosition()
        val stops = ready(repository, location, settings)
        val arrivals = stops.state.arrivals
        repository.nearbyCalls = 0

        settings.value = settings.value.copy(sortStopsByProximity = true)
        assertEquals(listOf("c", "b", "a"), ids(stops))

        settings.value = settings.value.copy(sortStopsByProximity = false)
        assertEquals(listOf("a", "b", "c"), ids(stops))

        // The arrivals do not change with the order
        assertEquals(0, repository.nearbyCalls)
        assertEquals(arrivals, stops.state.arrivals)
    }

    @Test
    fun aChangeMadeWhileALoadWaitsForTheLocationStays() = test {
        val repository = spread().apply { applyUpdates = true }
        // Closest first, with no last fix: a fresh one is waited for
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = here)
        val stops = ready(repository, location)
        val gate = CompletableDeferred<Unit>()
        location.waiting = { gate.await() }

        val load = stops.loadFavorites()
        stops.setShowOnHome("a", false).join()
        gate.complete(Unit)
        load.join()

        assertEquals(listOf("c", "b", "a"), ids(stops))
        assertFalse(stops.state.favorites.first { it.id == "a" }.showOnHome)
    }

    @Test
    fun aChangeSavedWhileALoadReadsIsReadAgain() = test {
        val repository = spread().apply { applyUpdates = true }
        val stops = ready(repository, settings = byPosition())
        val gate = CompletableDeferred<Unit>()
        repository.reading = { gate.await() }

        val load = stops.loadFavorites()
        repository.reading = null
        stops.setShowOnHome("a", false).join()
        stops.removeFavorite("c").join()
        gate.complete(Unit)
        load.join()

        assertFalse(stops.state.favorites.first { it.id == "a" }.showOnHome)
        assertEquals(listOf("a", "b"), ids(stops))
    }

    @Test
    fun aMoveMadeWhileALoadReadsStays() = test {
        val repository = spread().apply { applyUpdates = true }
        val stops = ready(repository, settings = byPosition())
        val gate = CompletableDeferred<Unit>()
        repository.reading = { gate.await() }

        val load = stops.loadFavorites()
        repository.reading = null
        stops.reorderFavorites(0, 3)
        gate.complete(Unit)
        load.join()

        assertEquals(listOf("b", "c", "a"), ids(stops))
    }

    @Test
    fun aChangeMadeWhileTheOrderWaitsForTheLocationStays() = test {
        val repository = spread()
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = here)
        val settings = byPosition()
        val stops = ready(repository, location, settings)
        val gate = CompletableDeferred<Unit>()
        location.waiting = { gate.await() }

        // Closest first, with no last fix: the order waits for a fresh one
        settings.value = settings.value.copy(sortStopsByProximity = true)
        stops.setShowOnHome("a", false).join()
        gate.complete(Unit)

        assertEquals(listOf("c", "b", "a"), ids(stops))
        assertFalse(stops.state.favorites.first { it.id == "a" }.showOnHome)
    }

    @Test
    fun otherSettingsLeaveTheOrderAlone() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = here)
        val settings = byPosition()
        val stops = ready(spread(), location, settings)
        // The location moves on, but only the order setting reorders
        location.lastKnown = GeoPoint(3.4700, -76.5320)

        settings.value = settings.value.copy(farePrice = 3500)

        assertEquals(listOf("a", "b", "c"), ids(stops))
    }

    // Location states, as the nearby search meets them

    @Test
    fun nearbyAsksAroundTheDeviceAndSaysWhere() = test {
        val repository = FakeStopsRepository(favorites = emptyList(), nearby = listOf(nearbyStop("a")))
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = here)
        val stops = ready(repository, location)

        val result = stops.nearbyStops()

        assertEquals(listOf("a"), result.stops.map { it.id })
        assertEquals(here, GeoPoint(result.latitude, result.longitude))
        assertEquals(listOf(here), repository.askedAround)
        assertEquals(LocationAccuracy.HIGH to 15.seconds, location.currentAsked.single())
    }

    @Test
    fun locationOffIsUnavailableWithoutAPrompt() = test {
        val location = FakeLocationSource(enabled = false)
        val stops = ready(FakeStopsRepository(emptyList(), emptyList()), location)

        val error = nearbyError(stops)

        assertFalse(error.permanentlyDenied)
        assertEquals(0, location.requests)
    }

    @Test
    fun aPermissionNotGivenIsAskedForAndUsed() = test {
        val location = FakeLocationSource(answer = LocationPermission.GRANTED, current = here)
        val stops = ready(FakeStopsRepository(emptyList(), emptyList()), location)

        stops.nearbyStops()

        assertEquals(1, location.requests)
        assertEquals(1, location.currentAsked.size)
    }

    @Test
    fun aPermissionRefusedIsUnavailable() = test {
        val location = FakeLocationSource(answer = LocationPermission.DENIED)
        val stops = ready(FakeStopsRepository(emptyList(), emptyList()), location)

        val error = nearbyError(stops)

        assertFalse(error.permanentlyDenied)
        assertTrue(location.currentAsked.isEmpty())
    }

    @Test
    fun aPermissionRefusedForGoodSendsToTheSettings() = test {
        val location = FakeLocationSource(answer = LocationPermission.DENIED_FOREVER)
        val stops = ready(FakeStopsRepository(emptyList(), emptyList()), location)

        assertTrue(nearbyError(stops).permanentlyDenied)
    }

    @Test
    fun noFixInTimeIsAnErrorOfItsOwn() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED)
        val stops = ready(FakeStopsRepository(emptyList(), emptyList()), location)

        try {
            stops.nearbyStops()
            fail("Expected no fix")
        } catch (e: Exception) {
            // Not a LocationUnavailableException: the tab says the arrivals failed
            assertFalse(e is LocationUnavailableException)
        }
    }

    private suspend fun nearbyError(stops: StopsViewModel): LocationUnavailableException {
        try {
            stops.nearbyStops()
        } catch (e: LocationUnavailableException) {
            return e
        }
        throw AssertionError("Expected the location to be unavailable")
    }

    // The station catalog, kept as stationsProvider keeps it

    @Test
    fun theCatalogLoadsOnceAndKeepsAFailureUntilAskedAgain() = test {
        val repository = FakeStopsRepository(favorites = emptyList(), nearby = emptyList())
        repository.catalogFailure = NetworkApiException("Connection error")
        val stops = ready(repository)

        stops.watchStations()
        stops.watchStations()
        assertTrue(stops.stations.value is Loaded.Failed)
        assertEquals(1, repository.stationCalls)

        repository.catalogFailure = null
        repository.stations = listOf(Station(1, "Estación Alamos", "Av 3N - Cl 52N", 3.48, -76.51))
        stops.watchStations()
        assertTrue(stops.stations.value is Loaded.Failed)

        stops.reloadStations()
        assertEquals("Estación Alamos", stops.stations.value.valueOrNull?.single()?.name)
        assertEquals(2, repository.stationCalls)
    }

    @Test
    fun theLookupsAskTheRepository() = test {
        val repository = FakeStopsRepository(
            favorites = emptyList(),
            nearby = listOf(nearbyStop("a")),
            lines = mapOf("a" to listOf(StopLine("P82", "TERMINAL CALIPSO"))),
        )
        val stops = ready(repository)

        assertEquals(listOf("a"), stops.nearbyAt(3.45, -76.53).map { it.id })
        assertEquals(listOf("P82"), stops.linesByStop("a").map { it.shortName })
        assertEquals(listOf(GeoPoint(3.45, -76.53)), repository.askedAround)
    }
}
