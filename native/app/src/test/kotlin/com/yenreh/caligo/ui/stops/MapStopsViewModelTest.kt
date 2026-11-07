@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.stops

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.CacheEntry
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.map.MapCamera
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Flutter MapStopsScreen's state: where the map opens, when a fix
 * moves it, the search under the crosshair, the stop picked and the
 * camera kept for next time
 */
@RunWith(AndroidJUnit4::class)
class MapStopsViewModelTest {
    private val store = ViewModelStore()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    private val cali = GeoPoint(3.4516, -76.5320)
    private val home = GeoPoint(3.4200, -76.5400)

    /** 100 m north of [home] */
    private val nearHome = GeoPoint(3.4200 + 100 / 111_320.0, -76.5400)

    private val cache = MemoryJsonCache()

    private fun TestScope.viewModel(
        location: FakeLocationSource = FakeLocationSource(),
        repository: FakeStopsRepository = FakeStopsRepository(emptyList(), emptyList()),
    ): MapStopsViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { MapStopsViewModel(repository, location, cache, backgroundScope) } },
    )[MapStopsViewModel::class]

    private val MapStopsViewModel.camera get() = screen.value.map!!.camera

    // Where it opens

    @Test
    fun opensOnTheLastKnownPositionCloseUp() = runTest {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = home)

        val model = viewModel(location)

        assertEquals(MapCamera(home, 16.0), model.camera)
    }

    @Test
    fun elseWhereTheMapWasLeft() = runTest {
        cache.put("map_camera", "[3.44, -76.52, 14.5]")

        val model = viewModel()

        assertEquals(MapCamera(GeoPoint(3.44, -76.52), 14.5), model.camera)
    }

    @Test
    fun elseOverTheCityCentre() = runTest {
        val model = viewModel()

        assertEquals(MapCamera(cali, 15.0), model.camera)
        assertEquals(11.0, model.screen.value.map!!.minZoom, 0.0)
        assertEquals(18.0, model.screen.value.map!!.maxZoom, 0.0)
    }

    @Test
    fun aSavedCameraThatIsNotThreeNumbersIsNone() = runTest {
        // The Flutter screen stayed blank on one; here the city centre shows
        cache.put("map_camera", "[3.44, \"-76.52\", 14.5]")

        assertEquals(MapCamera(cali, 15.0), viewModel().camera)
    }

    // The fix on opening

    @Test
    fun aFixFarFromWhereItOpenedMovesTheMapWithoutAsking() = runTest {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = home)

        val model = viewModel(location)

        assertEquals(MapCamera(home, 16.0), model.camera)
        assertEquals(0, location.requests)
        assertFalse(model.screen.value.locating)
    }

    @Test
    fun aFixCloseToWhereItOpenedLeavesItBe() = runTest {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = home, current = nearHome)

        val model = viewModel(location)

        assertEquals(MapCamera(home, 16.0), model.camera)
    }

    @Test
    fun withoutThePermissionOpeningNeverAsks() = runTest {
        val location = FakeLocationSource(current = home)

        val model = viewModel(location)

        assertEquals(0, location.requests)
        assertEquals(MapCamera(cali, 15.0), model.camera)
        assertFalse(model.screen.value.locating)
    }

    @Test
    fun locatingShowsUntilTheFixComes() = runTest {
        val fix = CompletableDeferred<Unit>()
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = home)
        location.waiting = { fix.await() }

        val model = viewModel(location)
        assertTrue(model.screen.value.locating)

        fix.complete(Unit)
        assertFalse(model.screen.value.locating)
        assertEquals(home, model.camera.center)
    }

    // The location button

    @Test
    fun theButtonAsksForThePermissionAndAlwaysMoves() = runTest {
        val location = FakeLocationSource(current = nearHome)
        val model = viewModel(location)
        model.screen.value.map!!.move(home, 13.0)

        model.centreOnDevice()

        assertEquals(1, location.requests)
        assertEquals(MapCamera(nearHome, 16.0), model.camera)
    }

    @Test
    fun withoutALocationTheMapStaysWhereItIs() = runTest {
        val location = FakeLocationSource(enabled = false)
        val model = viewModel(location)

        model.centreOnDevice()

        assertEquals(MapCamera(cali, 15.0), model.camera)
        assertFalse(model.screen.value.locating)
    }

    // Searching

    private val around = listOf(
        NearbyStop("500800", "Plaza de Cayzedo A1", 119.5),
        NearbyStop("500751", "La Ermita A2", 187.5),
    )

    @Test
    fun beforeAnySearchThereIsNothing() = runTest {
        val model = viewModel()

        assertNull(model.screen.value.searched)
        assertNull(model.screen.value.results)
    }

    @Test
    fun searchesAroundTheCrosshairAndPlacesTheStopsAfter() = runTest {
        val repository = FakeStopsRepository(emptyList(), around)
        repository.located = mapOf("500800" to GeoPoint(3.4513, -76.5331))
        val model = viewModel(repository = repository)
        model.screen.value.map!!.move(home)

        model.searchHere()

        assertEquals(home, model.screen.value.searched)
        assertEquals(listOf(home), repository.askedAround)
        val stops = (model.screen.value.results as Loaded.Ready).value
        assertEquals(listOf("500800", "500751"), stops.map { it.id })
        assertEquals(3.4513, stops.first().latitude!!, 0.0)
        assertFalse(stops.last().hasPosition)
    }

    @Test
    fun aSearchThatFailsSaysSo() = runTest {
        val repository = FakeStopsRepository(emptyList(), around)
        repository.failure = NetworkApiException("Connection error")
        val model = viewModel(repository = repository)

        model.searchHere()

        assertTrue(model.screen.value.results is Loaded.Failed)
    }

    @Test
    fun aNewSearchForgetsThePickedStop() = runTest {
        val model = viewModel(repository = FakeStopsRepository(emptyList(), around))
        model.searchHere()
        model.focus(around.first())
        assertEquals("500800", model.screen.value.selectedStopId)

        model.searchHere()

        assertNull(model.screen.value.selectedStopId)
    }

    // Picking a stop

    @Test
    fun aPlacedStopIsBroughtCloseUp() = runTest {
        val model = viewModel()
        val stop = NearbyStop("500800", "Plaza de Cayzedo A1", 119.5, latitude = 3.4513, longitude = -76.5331)

        model.focus(stop)

        assertEquals("500800", model.screen.value.selectedStopId)
        assertEquals(MapCamera(GeoPoint(3.4513, -76.5331), 17.0), model.camera)
    }

    @Test
    fun alreadyCloseTheZoomStays() = runTest {
        val model = viewModel()
        model.screen.value.map!!.move(cali, 16.5)
        val stop = NearbyStop("500800", "Plaza de Cayzedo A1", 119.5, latitude = 3.4513, longitude = -76.5331)

        model.focus(stop)

        assertEquals(16.5, model.camera.zoom, 0.0)
        assertEquals(GeoPoint(3.4513, -76.5331), model.camera.center)
    }

    @Test
    fun aStopNotPlacedIsPickedWhereTheMapIs() = runTest {
        val model = viewModel()

        model.focus(around.last())

        assertEquals("500751", model.screen.value.selectedStopId)
        assertEquals(MapCamera(cali, 15.0), model.camera)
    }

    // The camera kept

    @Test
    fun keepsWhereTheMapWasLeft() = runTest {
        val model = viewModel()
        model.screen.value.map!!.move(home, 14.0)

        model.saveCamera()
        runCurrent()

        assertEquals("[3.42,-76.54,14.0]", cache.text("map_camera"))
    }

    @Test
    fun aMapNeverMovedKeepsNothing() = runTest {
        val model = viewModel()

        model.saveCamera()
        runCurrent()

        assertNull(cache.text("map_camera"))
    }

    /** A cache in memory, filled with JSON texts */
    class MemoryJsonCache : JsonCache {
        private val values = mutableMapOf<String, JsonElement>()

        fun put(key: String, text: String) {
            values[key] = parseJson(text)
        }

        fun text(key: String) = values[key]?.toString()

        override suspend fun read(key: String, maxAge: Duration?) = values[key]

        override suspend fun readEntry(key: String, remember: Boolean) = values[key]?.let { CacheEntry(0, it) }

        override suspend fun write(key: String, value: JsonElement, remember: Boolean) {
            values[key] = value
        }

        override suspend fun prune(maxAge: Duration) = Unit
    }
}
