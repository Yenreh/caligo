@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.trips

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.AddressPrecision
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.GeocodedPlace
import com.yenreh.caligo.domain.TransitNetwork
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** place_picker_screen.dart's logic: what is listed for a query, addresses on request, the device and the map */
class PlacePickerViewModelTest {
    private val store = ViewModelStore()
    private val main = UnconfinedTestDispatcher()
    private val work = CoroutineScope(SupervisorJob() + main)

    @Before
    fun main() = Dispatchers.setMain(main)

    @After
    fun clear() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(main) { body() }

    private fun stop(id: String, name: String, latitude: Double, longitude: Double, sequence: Int) =
        LineStop(id, name, latitude, longitude, 0, sequence)

    private val stops = listOf(
        stop("1", "Universidades A1", 3.3700, -76.5300, 1),
        stop("2", "Universidades B1", 3.3702, -76.5302, 2),
        stop("3", "Cl 1 entre Kr 44 y 42", 3.42334, -76.55276, 3),
        stop("4", "Estación Calima", 3.4800, -76.5000, 4),
    )

    private val names = PlaceNames("My location", "Point on the map") { "Near $it" }

    private class Geocoder(var failure: Exception? = null) {
        val asked = mutableListOf<String>()

        suspend fun search(query: String): List<GeocodedPlace> {
            asked.add(query)
            failure?.let { throw it }
            return emptyList()
        }
    }

    private fun viewModel(
        geocoder: Geocoder = Geocoder(),
        location: FakeLocationSource = FakeLocationSource(permission = LocationPermission.GRANTED),
    ): PlacePickerViewModel {
        val repository = FakeStopsRepository(emptyList(), emptyList()).apply { routes = mapOf("A1" to stops) }
        val loader = TransitNetworkLoader(repository, work, building = main)
        return ViewModelProvider.create(
            store,
            viewModelFactory { initializer { PlacePickerViewModel(loader, geocoder::search, location, names) } },
        )[PlacePickerViewModel::class]
    }

    @Test
    fun looksAnAddressUpOnlyOnRequestFallingBackOnTheRouteStops() = test {
        val geocoder = Geocoder()
        val model = viewModel(geocoder)

        model.setQuery("  Calle 1 # 42-40 ")
        assertTrue(model.screen.value.offersAddress)
        assertFalse(model.screen.value.showsFound)
        assertTrue(geocoder.asked.isEmpty())

        model.findAddress()
        val state = model.screen.value
        assertEquals("Calle 1 # 42-40", state.searched)
        assertTrue(state.showsFound)
        assertFalse(state.searching)
        assertEquals(AddressPrecision.STOP, state.found.single().precision)
        assertEquals("Cl 1 entre Kr 44 y 42", state.found.single().name)
        assertEquals(listOf("Calle 1 # 42-40, Cali", "Calle 1 & Carrera 42, Cali"), geocoder.asked)

        // Typing on shows the row to look up again
        model.setQuery("Calle 1 # 42-4")
        assertFalse(model.screen.value.showsFound)
    }

    @Test
    fun textTooShortIsNotLookedUp() = test {
        val geocoder = Geocoder()
        val model = viewModel(geocoder)

        model.setQuery("Cl ")
        model.findAddress()

        assertFalse(model.screen.value.offersAddress)
        assertTrue(geocoder.asked.isEmpty())
    }

    @Test
    fun aSearchThatFailsFindsNothing() = test {
        val model = viewModel(Geocoder(failure = IllegalStateException("Bad state")))

        model.setQuery("Unicentro")
        model.findAddress()

        assertEquals("Unicentro", model.screen.value.searched)
        assertTrue(model.screen.value.found.isEmpty())
    }

    @Test
    fun theDeviceIsPickedWhereItWasLastSeenOrWhereItIsNow() = test {
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = GeoPoint(3.40, -76.50))
        val model = viewModel(location = location)

        model.useDevice()
        assertEquals(
            PlacePickerEvent.Picked(TripPlace(3.40, -76.50, "My location", isDevice = true)),
            model.events.first(),
        )

        location.lastKnown = null
        location.current = GeoPoint(3.41, -76.51)
        model.useDevice()
        assertEquals(
            PlacePickerEvent.Picked(TripPlace(3.41, -76.51, "My location", isDevice = true)),
            model.events.first(),
        )
        assertFalse(model.screen.value.locating)
    }

    @Test
    fun theDeviceIsAskedForOnceWhileItsFixIsOnItsWay() = test {
        val gate = CompletableDeferred<Unit>()
        val location = FakeLocationSource(permission = LocationPermission.GRANTED, current = GeoPoint(3.41, -76.51))
        location.waiting = { gate.await() }
        val model = viewModel(location = location)

        model.useDevice()
        assertTrue(model.screen.value.locating)
        // The Flutter row could be tapped again before its spinner showed
        model.useDevice()
        gate.complete(Unit)

        assertEquals(PlacePickerEvent.Picked(TripPlace(3.41, -76.51, "My location", isDevice = true)), model.events.first())
        assertEquals(1, location.currentAsked.size)
        assertFalse(model.screen.value.locating)
    }

    @Test
    fun withoutAPositionSaysSo() = test {
        val model = viewModel(location = FakeLocationSource(permission = LocationPermission.DENIED, answer = LocationPermission.DENIED))

        model.useDevice()

        assertEquals(PlacePickerEvent.CouldNotLocate, model.events.first())
        assertFalse(model.screen.value.locating)
    }

    @Test
    fun aPointOnTheMapIsNamedByAStopCloseBy() = test {
        val model = viewModel()

        assertEquals("Near Estación Calima", model.pointPlace(GeoPoint(3.4810, -76.5000)).name)
        assertEquals("Point on the map", model.pointPlace(GeoPoint(3.4900, -76.5000)).name)
    }

    @Test
    fun listsWhatMatchesWithoutAccentsOrCase() {
        val network = TransitNetwork.fromRoutes(mapOf("A1" to stops))
        val station = Station(1, "Estación Universidades", "Cl 13 - Kr 100", 3.37, -76.53)
        val favorite = FavoriteStop(id = "9", name = "La Ermita A2", customName = "Trabajo", anchorLatitude = 3.45, anchorLongitude = -76.53)

        assertEquals(listOf("Universidades A1", "Universidades B1"), PlaceFilter("universidades").stops(network).map { it.name })
        assertEquals(listOf("Estación Calima"), PlaceFilter("ESTACION").stops(network).map { it.name })
        // One letter lists no stops
        assertTrue(PlaceFilter("u").stops(network).isEmpty())
        assertEquals(listOf(station), PlaceFilter("kr 100").stations(listOf(station)))
        assertEquals(listOf(favorite), PlaceFilter("ermita").favorites(listOf(favorite)))
        assertEquals(listOf(favorite), PlaceFilter("").favorites(listOf(favorite)))
    }

    @Test
    fun listsEachStopNameOnceAndTwentyFiveAtMost() {
        val many = (1..40).map { stop("$it", "Calle $it", 3.40 + it * 0.001, -76.53, it) } +
            stop("x", "Calle 1", 3.30, -76.53, 41)
        val network = TransitNetwork.fromRoutes(mapOf("A1" to many))

        val listed = PlaceFilter("calle").stops(network)

        assertEquals(PlaceFilter.MAX_STOPS, listed.size)
        assertEquals(listed.map { it.name }.distinct(), listed.map { it.name })
    }

    @Test
    fun aFavoriteIsItsStopWhenTheRoutesKnowItAndItsAnchorOtherwise() {
        val network = TransitNetwork.fromRoutes(mapOf("A1" to stops))
        val known = FavoriteStop(id = "3", stopId = "3", name = "Cl 1", anchorLatitude = 3.0, anchorLongitude = -76.0)
        val area = FavoriteStop(id = "u", name = "Universidades", anchorLatitude = 3.1, anchorLongitude = -76.1)

        assertEquals(TripPlace(3.42334, -76.55276, "Cl 1"), favoritePlace(known, network))
        assertEquals(TripPlace(3.1, -76.1, "Universidades"), favoritePlace(area, network))
    }
}
