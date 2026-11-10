package com.yenreh.caligo.ui.trips

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.GeocodedPlace
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The planner's screens fed by fakes, for the screen, navigation and
 * screenshot tests. Everything runs on the main thread, where Robolectric
 * runs the test too, so each step lands before the next line: the route
 * network, the plans and the arrivals. Its clock moves only when told.
 *
 * Places sit on a grid of [STEP], about 440 m, from [LAT] and [LON].
 */
class TripFixtures(
    /** What the device's own position is called */
    val deviceName: String = "My location",
    /** What the place picker names its points */
    val names: PlaceNames = PlaceNames("My location", "Point on the map") { "Near $it" },
) {
    val store = ViewModelStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 2026-09-29 10:00, local time */
    var clock: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 10, 0)
    }.timeInMillis

    fun place(x: Double, y: Double, name: String = "($x, $y)") = TripPlace(LAT + y * STEP, LON + x * STEP, name)

    fun point(x: Double, y: Double) = GeoPoint(LAT + y * STEP, LON + x * STEP)

    /** A line east along [y], one stop at each step of [xs], named [prefix] and the step unless [names] says */
    fun line(prefix: String, xs: IntRange, y: Double = 0.0, names: List<String>? = null) = xs.mapIndexed { i, x ->
        LineStop("$prefix$x", names?.get(i) ?: "$prefix $x", LAT + y * STEP, LON + x * STEP, 0, i + 1)
    }

    /** The origin, where the device was last seen, and the destination */
    val from = place(0.0, 0.5)
    val to = place(12.0, 0.5, "Destination")

    /** One line, T1, east from the origin to the destination */
    fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf("T1" to line("a", 0..12))
    }

    /** The device last seen at the origin, with no fresh fix to give */
    fun location(lastKnown: GeoPoint? = GeoPoint(from.latitude, from.longitude)) =
        FakeLocationSource(permission = LocationPermission.GRANTED, lastKnown = lastKnown)

    fun loader(repository: FakeStopsRepository) = TransitNetworkLoader(repository, scope, building = Dispatchers.Main.immediate)

    fun planner(
        repository: FakeStopsRepository = repository(),
        location: FakeLocationSource = location(),
        destination: TripPlace? = to,
        hours: MutableStateFlow<Map<String, LineHours>?> = MutableStateFlow(emptyMap()),
        loader: TransitNetworkLoader = loader(repository),
        key: String = "planner",
        trips: com.yenreh.caligo.trips.TripControl? = null,
        notifications: com.yenreh.caligo.waits.NotificationAccess? = null,
    ): PlanTripViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer {
                PlanTripViewModel(
                    repository = repository,
                    network = loader,
                    hours = hours,
                    location = location,
                    deviceName = deviceName,
                    destination = destination,
                    work = scope,
                    now = { clock },
                    planning = Dispatchers.Main.immediate,
                    trips = trips,
                    notifications = notifications,
                )
            }
        },
    )[key, PlanTripViewModel::class]

    fun picker(
        repository: FakeStopsRepository = repository(),
        geocoder: Geocoder = Geocoder(),
        location: FakeLocationSource = location(),
        loader: TransitNetworkLoader = loader(repository),
        key: String = "picker",
    ): PlacePickerViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { PlacePickerViewModel(loader, geocoder::search, location, names) } },
    )[key, PlacePickerViewModel::class]

    fun bus(line: String, vehicle: String, minutes: Int) =
        BusArrival(line, "End", clock + minutes.minutes.inWholeMilliseconds, vehicle)

    /** An answer fetched now, listing [stops] with their buses */
    fun arrivals(vararg stops: Pair<String, List<BusArrival>>) = LiveArrivals(
        clock,
        stops.map { (id, buses) -> NearbyStop(id, "Stop $id", 0.0, buses) },
        answeredAt = stops.associate { (id, _) -> id to clock },
    )

    /** Every view model let go, and the work they left */
    fun clear() {
        store.clear()
        scope.cancel()
    }

    /** The device's geocoder: what each query finds, recorded; [answer] stands in for the search, when set */
    class Geocoder(var found: Map<String, List<GeocodedPlace>> = emptyMap()) {
        val asked = mutableListOf<String>()
        var answer: (suspend () -> Unit)? = null

        suspend fun search(query: String): List<GeocodedPlace> {
            asked.add(query)
            answer?.invoke()
            return found[query].orEmpty()
        }
    }

    companion object {
        const val LAT = 3.45
        const val LON = -76.53

        /** About 440 m between stops */
        const val STEP = 0.004
    }
}
