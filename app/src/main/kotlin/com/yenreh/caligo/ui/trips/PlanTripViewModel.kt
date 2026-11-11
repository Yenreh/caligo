package com.yenreh.caligo.ui.trips

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.R
import com.yenreh.caligo.data.json.dartDouble
import com.yenreh.caligo.data.location.LocationSource
import com.yenreh.caligo.data.location.currentDevicePosition
import com.yenreh.caligo.data.location.lastKnownDevicePosition
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.TransitNetwork
import com.yenreh.caligo.domain.TripPlanner
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.TripOption
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.entities.matching
import com.yenreh.caligo.domain.metersBetween
import com.yenreh.caligo.domain.repositories.PlacesRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.domain.rideReadings
import com.yenreh.caligo.domain.stopsWorthAsking
import com.yenreh.caligo.domain.timeTrip
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.trips.TripControl
import com.yenreh.caligo.ui.map.LabMapState
import com.yenreh.caligo.waits.NotificationAccess
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A field as the saved places change from [before] to [now]: a saved place
 * it shows under its new name, or once deleted under what it was called
 * where picked, so saving it again keeps that. Left as it is while its
 * place was never read, as before the first load.
 */
private fun TripPlace?.following(before: List<FavoritePlace>, now: List<FavoritePlace>): TripPlace? {
    val id = this?.savedId ?: return this
    now.firstOrNull { it.id == id }?.let { return copy(name = it.name) }
    if (before.none { it.id == id }) return this
    return copy(name = ownName, savedId = null, label = null)
}

/** An option as listed: its position among the plans, timed */
data class OptionRow(val index: Int, val option: TripOption, val expanded: Boolean)

/** What the area below the places shows, as the Flutter screen's _results picks it */
sealed interface PlanResults {
    /** The routes could not load: the empty state with Retry */
    data object NetworkFailed : PlanResults

    /** The routes loading, [done] lines of [total]; 0 of 0 before the catalog answers */
    data class LoadingRoutes(val done: Int, val total: Int) : PlanResults

    /** Planning, with no options yet: the spinner */
    data object Planning : PlanResults

    /** A place still to choose: the hint */
    data object Hint : PlanResults

    /** The planner found nothing */
    data object NoTrip : PlanResults

    /**
     * The options, by arrival: [replanning] while new ones are worked out,
     * [liveFailed] when some area of arrivals could not be asked about
     */
    data class Options(val rows: List<OptionRow>, val replanning: Boolean, val liveFailed: Boolean) : PlanResults
}

/** What the trip planner shows */
data class PlanTripState(
    val from: TripPlace? = null,
    val to: TripPlace? = null,
    /** Waiting for a fix of the device, which the locate button shows */
    val locating: Boolean = false,
    /** The route network as it loads */
    val network: NetworkLoad = NetworkLoad(),
    /** The ways the planner found, untimed; null until both places are known and the routes are in */
    val plans: List<TripOption>? = null,
    /** Each plan timed against the buses on their way, by its position in [plans] */
    val timed: Map<Int, TripOption> = emptyMap(),
    /**
     * Positions in [plans] as listed: by arrival, sorted again only when
     * fresh arrivals come in, so rows do not jump while being read
     */
    val order: List<Int> = emptyList(),
    /** The buses on their way, as last asked for */
    val live: LiveArrivals? = null,
    /** Asking for the arrivals: the refresh button spins and waits */
    val fetchingLive: Boolean = false,
    /**
     * The plan opened to its steps and drawn on the map, by its position
     * in [plans]; none at first, so all can be compared at a glance
     */
    val expanded: Int? = null,
    val planning: Boolean = false,
    /** Why the open option could not start as a trip, with the way to the settings */
    val tripMessage: TripMessage? = null,
    /** The places saved for quick plans, in the order saved */
    val places: List<FavoritePlace> = emptyList(),
) {
    /** The saved place the origin is, which fills its star */
    val savedFrom: FavoritePlace? get() = from?.let { places.matching(it) }

    /** The saved place the destination is, which fills its star */
    val savedTo: FavoritePlace? get() = to?.let { places.matching(it) }

    /** The saved places offered as quick plans: while there is nowhere to go yet */
    val quickPlans: List<FavoritePlace> get() = if (to == null) places else emptyList()

    /** The option open, timed */
    val open: TripOption? get() = expanded?.let { timed[it] }

    /** Whether the places can trade ends */
    val canSwap: Boolean get() = from != null || to != null

    /** Whether the refresh button shows */
    val canRefresh: Boolean get() = plans != null

    val results: PlanResults
        get() {
            if (network.failed) return PlanResults.NetworkFailed
            if (network.network == null) return PlanResults.LoadingRoutes(network.done, network.total)
            val plans = plans ?: return if (planning) PlanResults.Planning else PlanResults.Hint
            if (plans.isEmpty()) return PlanResults.NoTrip
            return PlanResults.Options(
                rows = order.mapNotNull { i -> timed[i]?.let { OptionRow(i, it, i == expanded) } },
                replanning = planning,
                liveFailed = live?.failed ?: false,
            )
        }
}

/** What the planner reports with a snackbar */
enum class PlanTripMessage {
    /** The locate button found no position */
    COULD_NOT_LOCATE,

    /** Planning failed, by a read or the planner itself */
    COULD_NOT_PLAN,

    /** A place could not be saved, renamed or deleted */
    COULD_NOT_SAVE_PLACE,
}

/**
 * The Flutter PlanTripScreen's state with trip_provider.dart: from one
 * place to another on the MIO, where to walk, which buses to take and
 * where to change, each option timed against the buses on their way.
 *
 * The route network loads once for the run ([TransitNetworkLoader]); a
 * plan runs on [planning], off the main thread, as Dart's compute ran it
 * in an isolate. Every [retimeEvery] the options are timed again from the
 * arrivals in hand, and the open option's arrivals asked for again once
 * older than two minutes, only while the screen is in view ([setShown]).
 *
 * One per planner screen, kept while it turns.
 */
class PlanTripViewModel(
    private val repository: StopsRepository,
    private val network: TransitNetworkLoader,
    /** Hours each line runs, null until they come: lines not running are left out */
    private val hours: StateFlow<Map<String, LineHours>?>,
    private val location: LocationSource,
    /** What the device's own position is called */
    private val deviceName: String,
    /** Where to go, when opened from a place already known, such as a stop */
    destination: TripPlace? = null,
    /** Outlives the screen: what is learned of the lines is saved on it */
    private val work: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val planning: CoroutineDispatcher = Dispatchers.Default,
    private val retimeEvery: Duration = RETIME_EVERY,
    /** Where the open option starts as a trip; none offers no "Start trip" */
    private val trips: TripControl? = null,
    private val notifications: NotificationAccess? = null,
    /** The places saved for quick plans, which the stars save and the chips plan to */
    private val savedPlaces: PlacesRepository,
) : ViewModel() {
    private val state = MutableStateFlow(PlanTripState(to = destination, network = network.load.value))
    val screen: StateFlow<PlanTripState> = state.asStateFlow()

    private val reports = Channel<PlanTripMessage>(Channel.BUFFERED)
    val messages: Flow<PlanTripMessage> = reports.receiveAsFlow()

    private val placeEdits = SavedPlaceEdits(savedPlaces, viewModelScope) {
        reports.trySend(PlanTripMessage.COULD_NOT_SAVE_PLACE)
    }

    private val deleted = Channel<FavoritePlace>(Channel.BUFFERED)

    /** A saved place just deleted, as it was, for the snackbar's Undo */
    val deletedPlaces: Flow<FavoritePlace> = deleted.receiveAsFlow()

    /**
     * The fields a deletion left showing what their place was called where
     * picked, by what they show now, with the place they showed: should it
     * come back as it was, by Undo, they show it again
     */
    private var unlinked: Map<TripPlace, FavoritePlace> = emptyMap()

    private val started = Channel<Unit>(Channel.CONFLATED)

    /** A trip started from here, for the screen to open it */
    val tripsStarted: Flow<Unit> = started.receiveAsFlow()

    /** Whether the open option can start as a trip */
    val canStartTrips: Boolean get() = trips != null && notifications != null

    /**
     * Follow the open option as a trip: its walks, rides and changes, from
     * this screen on show, after what a trip needs, as the trip screen asks
     */
    fun startTrip() {
        val trips = trips ?: return
        val notifications = notifications ?: return
        val option = state.value.open ?: return
        if (option.rides.isEmpty()) return
        val destination = state.value.to?.name ?: option.rides.last().alight.name
        viewModelScope.launch {
            val missing = tripNeeds(notifications, location, trips)
            if (missing != null) {
                state.update { it.copy(tripMessage = missing) }
                return@launch
            }
            trips.startJourney(option, destination, rideFactors)
            started.trySend(Unit)
        }
    }

    fun dismissTripMessage() = state.update { it.copy(tripMessage = null) }

    /** The notification settings, for the message that asks for them: the trips' channel, or the app's */
    fun openNotificationSettings() {
        dismissTripMessage()
        trips?.openSettings()
    }

    /** Line chosen for a ride by tapping it, by plan and then by ride */
    private val pinned = HashMap<Int, MutableMap<Int, String>>()
    private var headways: Map<String, Duration> = emptyMap()

    /** What each line's rides were planned with, to read the live ones against the route alone */
    private var rideFactors: Map<String, Double> = emptyMap()

    private var shown = false

    /** Counts plans asked for, so one finishing after a newer one started is dropped */
    private var planRequest = 0

    private var fetchingLive = false

    /**
     * The buses of the answers asked for, at the stops the plans board at,
     * so one that reaches its stop still counts while it is there
     */
    private var fading = FadingArrivals.EMPTY

    /**
     * Set when arrivals were wanted while others were on their way, or came
     * back for plans or an option no longer shown: asked for again once
     * those land, mostly from the areas they just brought
     */
    private var liveAgain = false

    /** Counts the options opened, so each opening frames its own map */
    private var openings = 0

    /** The open option's map, with the opening it was made for */
    private var map: Pair<Int, LabMapState>? = null

    init {
        network.watch()
        viewModelScope.launch {
            var had = network.load.value.network != null
            network.load.collect { load ->
                state.update { it.copy(network = load) }
                val has = load.network != null
                if (has && !had) plan()
                had = has
            }
        }
        viewModelScope.launch {
            // Loaded alongside, to leave out the lines that are not running
            var had = hours.value != null
            hours.collect {
                if (it != null && !had) plan()
                had = it != null
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(retimeEvery)
                tick()
            }
        }
        // Most trips start where the phone is; asked quietly, so no prompt
        // stands in front of someone who has not chosen anything yet
        viewModelScope.launch { startFromDevice() }
        viewModelScope.launch {
            savedPlaces.places.collect { places ->
                state.update {
                    it.copy(places = places, from = follow(it.from, it.places, places), to = follow(it.to, it.places, places))
                }
            }
        }
        placeEdits.load()
    }

    /** Plan to a saved place, from the origin as it is */
    fun planTo(place: FavoritePlace) = choose(origin = false, place.tripPlace)

    /** Save the origin or the destination under [name]; never the device, nor a place saved already */
    fun savePlace(origin: Boolean, name: String) {
        val current = state.value
        val place = (if (origin) current.from else current.to) ?: return
        if (place.isDevice || FavoritePlace.cleanName(name) == null || current.places.matching(place) != null) return
        placeEdits.add(name, place)
    }

    /** Rename a saved place; a field showing it follows, as with a rename in the picker */
    fun renamePlace(id: Long, name: String) = placeEdits.rename(id, name)

    fun deletePlace(id: Long) = placeEdits.delete(id) { deleted.trySend(it) }

    /** Undo the deletion of [place]: it comes back as it was, and a field that showed it shows it again */
    fun restorePlace(place: FavoritePlace) = placeEdits.restore(place)

    /** [field] as the saved places change from [before] to [now], put back as it was when its place comes back */
    private fun follow(field: TripPlace?, before: List<FavoritePlace>, now: List<FavoritePlace>): TripPlace? {
        unlinked[field]?.let { gone -> now.firstOrNull { it.copy(id = gone.id) == gone }?.let { return it.tripPlace } }
        val followed = field.following(before, now)
        val id = field?.savedId
        if (followed != null && id != null && followed.savedId == null) {
            before.firstOrNull { it.id == id }?.let { unlinked = unlinked + (followed to it) }
        }
        return followed
    }

    /** Out of view, as under the trip screen, nothing is retimed: back in view it catches up */
    private fun tick() {
        val current = state.value
        if (current.plans == null || !shown) return
        val live = current.live
        val stale = live == null || now() - live.fetchedAt > LIVE_MAX_AGE.inWholeMilliseconds
        if (current.expanded != null && shown && stale) refreshLive() else retime()
    }

    /**
     * Whether the screen is in view. Back in view, the open trip catches up
     * at once if its arrivals aged meanwhile, and the times at once; out of
     * view, under the place picker or the trip screen or in the background,
     * nothing is asked for nor retimed.
     */
    fun setShown(shown: Boolean) {
        if (shown == this.shown) return
        this.shown = shown
        if (shown) tick()
    }

    private fun devicePlace(position: GeoPoint) =
        TripPlace(position.latitude, position.longitude, deviceName, isDevice = true)

    /** Start at once from where the device was last seen, as the map does, then refine with a fresh fix */
    private suspend fun startFromDevice() {
        val known = location.lastKnownDevicePosition()
        if (known != null && state.value.from == null) {
            state.update { it.copy(from = devicePlace(known)) }
            plan()
        }
        locate()
    }

    /**
     * Ask for a fresh fix. Quietly, it only refines an origin that is the
     * device and has moved; [force] is the button, which always takes it
     * and may ask for the permission.
     */
    fun locate(force: Boolean = false) {
        if (state.value.locating) return
        state.update { it.copy(locating = true) }
        viewModelScope.launch {
            try {
                val position = location.currentDevicePosition(requestPermission = force)
                val from = state.value.from
                if (!force && from != null) {
                    if (!from.isDevice) return@launch
                    val moved = metersBetween(from.latitude, from.longitude, position.latitude, position.longitude)
                    if (moved < REFINE_METERS) return@launch
                }
                state.update { it.copy(from = devicePlace(position)) }
                plan()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Quietly, no location only means the origin waits to be chosen
                if (force) reports.trySend(PlanTripMessage.COULD_NOT_LOCATE)
            } finally {
                state.update { it.copy(locating = false) }
            }
        }
    }

    /** Where the place picker for one end opens its map: near that end, else the other */
    fun pickerNear(origin: Boolean): TripPlace? {
        val current = state.value
        return if (origin) current.from ?: current.to else current.to ?: current.from
    }

    /** Take [place], from the place picker, as the origin or the destination */
    fun choose(origin: Boolean, place: TripPlace) {
        state.update { if (origin) it.copy(from = place) else it.copy(to = place) }
        plan()
        // The picker may hand over where the device was last seen
        if (place.isDevice) locate()
    }

    fun swap() {
        state.update { it.copy(from = it.to, to = it.from) }
        plan()
    }

    /** Load the routes again, from the failure's Retry */
    fun retryNetwork() = network.reload()

    private fun plan() {
        val request = ++planRequest
        val current = state.value
        val from = current.from
        val to = current.to
        val routes: TransitNetwork? = network.load.value.network
        if (from == null || to == null || routes == null) {
            state.update { it.copy(plans = null, timed = emptyMap(), order = emptyList(), expanded = null, planning = false) }
            return
        }

        // Lines not running now would only send someone to an empty stop
        val now = now()
        val leaveOut = (hours.value ?: emptyMap()).filterValues { !it.runsAt(now) }.keys

        state.update { it.copy(planning = true) }
        viewModelScope.launch {
            try {
                // What has been learned of each line picks the options, as it times them
                val headways = repository.lineHeadways(now)
                val rideFactors = repository.rideFactors()
                val options = withContext(planning) {
                    TripPlanner(routes, headways, rideFactors).plan(from, to, leaveOut)
                }
                if (request != planRequest) return@launch
                this@PlanTripViewModel.headways = headways
                this@PlanTripViewModel.rideFactors = rideFactors
                pinned.clear()
                state.update { it.copy(plans = options, expanded = null, planning = false) }
                // Timed at once by the usual figures, then again with the live buses
                retime(sort = true)
                refreshLive()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (request != planRequest) return@launch
                // Dart let such an error go unhandled, the spinner on for
                // good: what is shown stays, and the planner says so
                state.update { it.copy(planning = false) }
                reports.trySend(PlanTripMessage.COULD_NOT_PLAN)
            }
        }
    }

    /**
     * Ask for the buses on their way to the stops the plans call at: a few
     * requests, one per area, reusing any area asked about recently. With
     * an option open only its stops are asked about, and the rest of the
     * list keeps what it had. [force] is the refresh button, which reuses
     * no area.
     */
    fun refreshLive(force: Boolean = false) {
        val plans = state.value.plans ?: return
        if (fetchingLive) {
            liveAgain = true
            return
        }
        val open = state.value.expanded
        fetchingLive = true
        state.update { it.copy(fetchingLive = true) }
        viewModelScope.launch {
            try {
                val now = now()
                val headways = repository.lineHeadways(now)
                val stops = stopsWorthAsking(
                    if (open == null) plans else listOf(plans[open]),
                    start = now,
                    headways = headways,
                )
                val live = if (stops.isEmpty()) {
                    LiveArrivals.NONE
                } else {
                    repository.arrivalsFor(stops, maxAge = if (force) Duration.ZERO else 1.minutes)
                }
                if (plans !== state.value.plans) {
                    liveAgain = true
                    return@launch
                }
                val expanded = state.value.expanded
                if (open != null && expanded != null && open != expanded) liveAgain = true
                this@PlanTripViewModel.headways = headways
                fading = live.feed(fading).only(boardStops(plans))
                state.update { it.copy(live = if (open == null) live else live.over(it.live)) }
                retime(sort = true)
                learnRides(plans)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Dart let such an error go unhandled: the arrivals stay as they were
            } finally {
                if (isActive) {
                    fetchingLive = false
                    state.update { it.copy(fetchingLive = false) }
                    if (liveAgain) {
                        liveAgain = false
                        refreshLive()
                    }
                }
            }
        }
    }

    /**
     * Rides just timed by following their bus teach the planner how each
     * line runs against its estimate: one reading per line
     */
    private fun learnRides(plans: List<TripOption>) {
        val readings = LinkedHashMap<String, Double>()
        val timed = state.value.timed
        for ((i, plan) in plans.withIndex()) {
            timed[i]?.let { readings.putAll(rideReadings(plan, it, rideFactors)) }
        }
        if (readings.isEmpty()) return
        work.launch {
            try {
                repository.observeRides(readings)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Learning is a bonus: the trip is timed all the same
            }
        }
    }

    /** Time every plan from now with what is known, without asking */
    private fun retime(sort: Boolean = false) {
        val current = state.value
        val plans = current.plans ?: return
        val now = now()
        val live = current.live?.copy(atTheStop = fading.atTheStop(now))
        val timed = plans.indices.associateWith { i ->
            timeTrip(plans[i], start = now, live = live, headways = headways, pinned = pinned[i].orEmpty())
        }
        val order = if (sort || current.order.size != plans.size) {
            // Ranked as the planner ranks them, now with the buses on their way
            plans.indices.sortedBy { TripPlanner.cost(timed.getValue(it)) }
        } else {
            current.order
        }
        state.update { it.copy(timed = timed, order = order) }
    }

    /** Every stop [plans] board at, by any of their lines */
    private fun boardStops(plans: List<TripOption>): Set<String> = buildSet {
        for (plan in plans) {
            for (ride in plan.rides) {
                for (variant in ride.variants.ifEmpty { listOf(ride) }) add(variant.board.stopId)
            }
        }
    }

    /** Show ride [rideIndex] of plan [plan] as made by [line] */
    fun chooseLine(plan: Int, rideIndex: Int, line: String) {
        pinned.getOrPut(plan) { HashMap() }[rideIndex] = line
        retime()
    }

    /** Open plan [index] to its steps and the map, or close it when open */
    fun toggle(index: Int) {
        // Each opening gets a map of its own, framing the whole trip
        openings++
        state.update { it.copy(expanded = if (it.expanded != index) index else null) }
    }

    /**
     * The map of the option open, for a map of [viewport]: made as it
     * opens, framing the whole trip, and kept while it stays open, so the
     * map turns with the screen where it was left; choosing another line
     * only redraws it. Null with no option open.
     */
    fun mapFor(viewport: DpSize): LabMapState? {
        val current = state.value
        val option = current.open ?: return null
        map?.let { (opening, kept) -> if (opening == openings) return kept }
        return LabMapState.fitting(
            tripPoints(current.from, current.to, option),
            viewport,
            MAP_PADDING,
            minZoom = MAP_MIN_ZOOM,
            maxZoom = MAP_MAX_ZOOM,
            rotatable = false,
            fitMaxZoom = MAP_FIT_MAX_ZOOM,
        ).also { map = openings to it }
    }

    companion object {
        /**
         * Times are worked out again this often from the arrivals already in
         * hand: a bus due at a set time only needs the clock to move on
         */
        val RETIME_EVERY = 20.seconds

        /**
         * Arrivals older than this are asked for again, but only while a
         * trip is open and the screen in view
         */
        val LIVE_MAX_AGE = 2.minutes

        /**
         * A fresh fix closer than this to the origin in use leaves the plan
         * as it is: replanning would only shuffle the same options
         */
        const val REFINE_METERS = 100.0

        /** The open option's map, a trip reading north up */
        private val MAP_PADDING = 32.dp
        private const val MAP_MIN_ZOOM = 11.0
        private const val MAP_MAX_ZOOM = 18.0

        /** No closer than this to frame a short trip */
        private const val MAP_FIT_MAX_ZOOM = 16.0

        /** The planner for a screen; [lines] gives the hours, which it loads */
        fun factory(lines: LinesViewModel, destination: TripPlace?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CaliGoApp
                val container = app.container
                lines.watchHours()
                PlanTripViewModel(
                    repository = container.stopsRepository,
                    network = container.transitNetwork,
                    hours = lines.loadedHours,
                    location = container.location,
                    deviceName = app.getString(R.string.my_location),
                    destination = destination,
                    work = container.scope,
                    trips = container.trips,
                    notifications = container.notificationAccess,
                    savedPlaces = container.placesRepository,
                )
            }
        }
    }
}

/** Every point of [option] and both places, to show it whole */
fun tripPoints(from: TripPlace?, to: TripPlace?, option: TripOption): List<GeoPoint> = buildList {
    from?.let { add(GeoPoint(it.latitude, it.longitude)) }
    to?.let { add(GeoPoint(it.latitude, it.longitude)) }
    for (ride in option.rides) for (stop in ride.stops) add(GeoPoint(stop.latitude, stop.longitude))
}

/**
 * The same search in Google Maps, to compare with its own routes: the
 * address the Flutter screen opened, its query written as Dart's
 * Uri.https writes one
 */
fun googleMapsDirections(from: TripPlace, to: TripPlace): String {
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    val query = listOf(
        "api" to "1",
        "origin" to "${dartDouble(from.latitude)},${dartDouble(from.longitude)}",
        "destination" to "${dartDouble(to.latitude)},${dartDouble(to.longitude)}",
        "travelmode" to "transit",
    ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    return "https://www.google.com/maps/dir/?$query"
}
