package com.yenreh.caligo.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.R
import com.yenreh.caligo.data.location.LocationAccuracy
import com.yenreh.caligo.data.location.LocationSource
import com.yenreh.caligo.data.location.currentDevicePosition
import com.yenreh.caligo.data.location.lastKnownDevicePosition
import com.yenreh.caligo.domain.AddressMatch
import com.yenreh.caligo.domain.AddressPrecision
import com.yenreh.caligo.domain.AddressSearch
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.GeocodedPlace
import com.yenreh.caligo.domain.TransitNetwork
import com.yenreh.caligo.domain.dartTrim
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.foldAccents
import com.yenreh.caligo.domain.repositories.PlacesRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the place picker names the places it hands over */
class PlaceNames(
    val myLocation: String,
    val pointOnMap: String,
    val nearStop: (stop: String) -> String,
)

/** What the place picker shows besides its lists */
data class PlacePickerState(
    /** The search box as typed */
    val query: String = "",
    /**
     * The text last looked up as an address or place, and what it found:
     * shown while the box still holds that text
     */
    val searched: String? = null,
    val found: List<AddressMatch> = emptyList(),
    val searching: Boolean = false,
    /** Waiting for a fix of the device */
    val locating: Boolean = false,
) {
    /** The box, trimmed as Dart trims */
    val typed: String get() = query.dartTrim()

    /** Whether the address row shows: three letters typed at least */
    val offersAddress: Boolean get() = typed.length >= MIN_ADDRESS_LENGTH

    /** Whether the address results show, rather than the row to look them up */
    val showsFound: Boolean get() = offersAddress && searched == typed

    /** The lists' filter for what was typed */
    val filter: PlaceFilter get() = PlaceFilter(typed)
}

/** What the place picker reports */
sealed interface PlacePickerEvent {
    /** A place chosen, which the picker hands back */
    data class Picked(val place: TripPlace) : PlacePickerEvent

    /** The device could not be located: a snackbar */
    data object CouldNotLocate : PlacePickerEvent

    /** A saved place could not be changed: a snackbar */
    data object CouldNotSavePlace : PlacePickerEvent

    /** A saved place deleted, as it was: a snackbar with Undo */
    data class PlaceDeleted(val place: FavoritePlace) : PlacePickerEvent
}

/** Three letters typed at least before an address is looked up */
private const val MIN_ADDRESS_LENGTH = 3

/**
 * The lists of the place picker for what was [typed]: lowercase and
 * without accents, so "universidades" finds "Universidades" and
 * "estacion" finds "Estación"
 */
class PlaceFilter(typed: String) {
    private val needle = foldAccents(typed)

    fun matches(text: String) = needle.isEmpty() || foldAccents(text).contains(needle)

    /** Saved places by their own name or what they were called */
    fun places(places: List<FavoritePlace>) = places.filter { matches(it.name) || matches(it.label) }

    fun favorites(favorites: List<FavoriteStop>) = favorites.filter { matches(it.displayName) || matches(it.name) }

    fun stations(stations: List<Station>) = stations.filter { matches(it.name) || matches(it.address) }

    /**
     * Stops along the routes matching, each name once, from two letters
     * typed and [MAX_STOPS] at most: the list is for picking, not browsing
     * every platform in the city
     */
    fun stops(network: TransitNetwork?): List<LineStop> {
        if (needle.length < 2 || network == null) return emptyList()
        val seen = HashSet<String>()
        return network.stops.asSequence().filter { matches(it.name) && seen.add(it.name) }.take(MAX_STOPS).toList()
    }

    companion object {
        const val MAX_STOPS = 25
    }
}

/**
 * A favorite as a place: the stop itself when the routes know it; its
 * anchor, a few metres off, otherwise. An area, with no stop of its own,
 * is a plain place.
 */
fun favoritePlace(favorite: FavoriteStop, network: TransitNetwork?): TripPlace {
    val stop = favorite.stopId?.let { network?.stopById(it) }
    return TripPlace(
        stop?.latitude ?: favorite.anchorLatitude,
        stop?.longitude ?: favorite.anchorLongitude,
        favorite.displayName,
        kind = if (favorite.stopId == null) PlaceKind.PLACE else PlaceKind.STOP,
        detail = favorite.secondaryName,
        stopId = favorite.stopId,
    )
}

/** A stop along the routes as a place */
fun stopPlace(stop: LineStop) = TripPlace(stop.latitude, stop.longitude, stop.name, kind = PlaceKind.STOP, stopId = stop.stopId)

/** A station as a place: it spans several stops, so it is a plain place */
fun stationPlace(station: Station) = TripPlace(station.latitude, station.longitude, station.name, detail = station.address)

/** What an address found names and where it is: an address, or a place with its own */
fun addressPlace(match: AddressMatch) = TripPlace(
    match.latitude,
    match.longitude,
    match.name,
    kind = if (match.precision == AddressPrecision.PLACE) PlaceKind.PLACE else PlaceKind.ADDRESS,
    detail = match.detail.takeIf { match.precision == AddressPrecision.PLACE },
)

/**
 * The Flutter PlacePickerScreen's logic: choose where a trip starts or
 * ends, from the device, a point on the map, a saved place, a favorite, a
 * station, any stop along the routes, or an address or place looked up on
 * request. Saved places are renamed or deleted here too.
 *
 * One per picker screen.
 */
class PlacePickerViewModel(
    private val network: TransitNetworkLoader,
    /** Places a geocoder finds, within Cali: the device's */
    private val geocode: suspend (query: String) -> List<GeocodedPlace>,
    private val location: LocationSource,
    private val names: PlaceNames,
    private val savedPlaces: PlacesRepository,
) : ViewModel() {
    private val state = MutableStateFlow(PlacePickerState())
    val screen: StateFlow<PlacePickerState> = state.asStateFlow()

    private val reports = Channel<PlacePickerEvent>(Channel.BUFFERED)
    val events: Flow<PlacePickerEvent> = reports.receiveAsFlow()

    /** The routes, for the stops listed and named */
    val routes: StateFlow<NetworkLoad> get() = network.load

    /** The places saved for quick plans, listed first */
    val places: StateFlow<List<FavoritePlace>> get() = savedPlaces.places

    private val placeEdits = SavedPlaceEdits(savedPlaces, viewModelScope) {
        reports.trySend(PlacePickerEvent.CouldNotSavePlace)
    }

    init {
        network.watch()
        placeEdits.load()
    }

    fun renamePlace(id: Long, name: String) = placeEdits.rename(id, name)

    fun deletePlace(id: Long) = placeEdits.delete(id) { reports.trySend(PlacePickerEvent.PlaceDeleted(it)) }

    /** Undo the deletion of [place]: it comes back as it was */
    fun restorePlace(place: FavoritePlace) = placeEdits.restore(place)

    fun setQuery(query: String) = state.update { it.copy(query = query) }

    /**
     * Look the text up as an address or a place: only on request, since
     * each search asks Google through the phone
     */
    fun findAddress() {
        val text = state.value.query.dartTrim()
        if (text.length < MIN_ADDRESS_LENGTH || state.value.searching) return
        state.update { it.copy(searching = true) }
        val stops = network.load.value.network?.stops.orEmpty()
        viewModelScope.launch {
            val found = try {
                AddressSearch(geocode, stops).find(text)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // A search that fails finds nothing: the map is still there
                emptyList()
            }
            state.update { it.copy(searching = false, searched = text, found = found) }
        }
    }

    /**
     * The device: where it was last seen answers at once, and the planner
     * then refines it quietly; without one, a fresh fix at balanced
     * accuracy, the fastest the platform gives, asking for the permission.
     * Once at a time: the Flutter row could be tapped again before its
     * spinner showed.
     */
    fun useDevice() {
        if (state.value.locating) return
        state.update { it.copy(locating = true) }
        viewModelScope.launch {
            try {
                val position = location.lastKnownDevicePosition() ?: location.currentDevicePosition(accuracy = LocationAccuracy.BALANCED)
                reports.send(
                    PlacePickerEvent.Picked(
                        TripPlace(position.latitude, position.longitude, names.myLocation, isDevice = true),
                    ),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reports.send(PlacePickerEvent.CouldNotLocate)
            } finally {
                state.update { it.copy(locating = false) }
            }
        }
    }

    /** A point picked on the map: a stop close by names it better than its coordinates */
    fun pointPlace(point: GeoPoint): TripPlace {
        val stop = network.load.value.network?.nearestStop(point.latitude, point.longitude, radius = POINT_STOP_METERS)
        return TripPlace(point.latitude, point.longitude, if (stop == null) names.pointOnMap else names.nearStop(stop.name))
    }

    companion object {
        /** A stop this close to a point picked on the map names it */
        const val POINT_STOP_METERS = 250.0

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CaliGoApp
                val container = app.container
                PlacePickerViewModel(
                    network = container.transitNetwork,
                    geocode = container.geocoder::search,
                    location = container.location,
                    names = PlaceNames(
                        myLocation = app.getString(R.string.my_location),
                        pointOnMap = app.getString(R.string.point_on_map),
                        nearStop = { app.getString(R.string.near_stop, it) },
                    ),
                    savedPlaces = container.placesRepository,
                )
            }
        }
    }
}
