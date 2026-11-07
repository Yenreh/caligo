package com.yenreh.caligo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ApplicationProvider
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.trips.PlacePickerViewModel
import com.yenreh.caligo.ui.trips.PlanTripViewModel
import com.yenreh.caligo.ui.trips.TripFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app as MainActivity shows it, with the trip planner and its picker
 * made over [TripFixtures]: one fake repository, location and route
 * network, as the app's container shares its own. Records every planner
 * and picker made.
 */
class PlannerApp {
    val trips = TripFixtures()

    val station = Route.Station(12, "Estación Universidades", "Cl 16 - Kr 100", trips.to.latitude, trips.to.longitude)

    val repository = trips.repository().apply {
        stations = listOf(Station(station.id, station.name, station.address, station.latitude, station.longitude))
    }

    /** a 12, saved under a name of its own */
    val favorites = listOf(
        FavoriteStop(id = "a12", stopId = "a12", name = "a 12", customName = "Trabajo", anchorLatitude = 0.0, anchorLongitude = 0.0),
    )
    val location = trips.location()
    val loader = trips.loader(repository)

    /** The planners made, with where each was to go */
    val planners = mutableListOf<Pair<PlanTripViewModel, TripPlace?>>()
    val pickers = mutableListOf<PlacePickerViewModel>()

    private val container get() = ApplicationProvider.getApplicationContext<CaliGoApp>().container

    fun clear() = trips.clear()

    /** The activity's stops and lines, put in [owner] before the screens ask for them; kept when already there */
    fun activityModels(owner: ViewModelStoreOwner, nearby: List<NearbyStop> = emptyList()) {
        container.settings.setHomeContent(HomeContent.STOPS)
        val stops = FakeStopsRepository(favorites, nearby).apply {
            routes = repository.routes
            stations = repository.stations
        }
        ViewModelProvider.create(
            owner,
            viewModelFactory {
                initializer { StopsViewModel(stops, location = FakeLocationSource(), settings = container.settings.settings) }
            },
        )[StopsViewModel::class]
        ViewModelProvider.create(owner, viewModelFactory { initializer { LinesViewModel(stops) } })[LinesViewModel::class]
    }

    @Composable
    fun Content(backStack: List<NavKey> = listOf(Route.Home)) {
        val settings by container.settings.settings.collectAsStateWithLifecycle()
        CaliGoTheme {
            AppNavigation(
                settings,
                backStack = rememberNavBackStack(*backStack.toTypedArray()),
                mapTiles = { remember { FixtureTiles() } },
                planTripViewModel = { _, destination ->
                    viewModel(
                        factory = viewModelFactory {
                            initializer {
                                PlanTripViewModel(
                                    repository = repository,
                                    network = loader,
                                    hours = MutableStateFlow(emptyMap()),
                                    location = location,
                                    deviceName = trips.deviceName,
                                    destination = destination,
                                    work = trips.scope,
                                    now = { trips.clock },
                                    planning = Dispatchers.Main.immediate,
                                ).also { planners.add(it to destination) }
                            }
                        },
                    )
                },
                placePickerViewModel = {
                    viewModel(
                        factory = viewModelFactory {
                            initializer {
                                PlacePickerViewModel(loader, TripFixtures.Geocoder()::search, location, trips.names)
                                    .also { pickers.add(it) }
                            }
                        },
                    )
                },
            )
        }
    }
}
