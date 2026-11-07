package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.json.JsonFormatException
import com.yenreh.caligo.data.json.dartDouble
import com.yenreh.caligo.data.json.dartString
import com.yenreh.caligo.data.json.double
import com.yenreh.caligo.data.json.doubleOrNull
import com.yenreh.caligo.data.json.isNum
import com.yenreh.caligo.data.json.long
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.domain.ARRIVALS_REACH_METERS
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.LiveArrivals
import com.yenreh.caligo.domain.areasCovering
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.domain.entities.Station
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.domain.metersBetween
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * Stops around a point and the buses on their way to them, from the
 * arrivals service, and the station catalog and the lines serving a stop,
 * from the catalog server: those paths of the Flutter app's datasource.
 * The lines, routes and buses come with their screens.
 */
class StopsRemoteDatasource(
    client: OkHttpClient,
    private val cache: JsonCache,
    private val backoff: Duration = 600.milliseconds,
    /**
     * The arrivals and the catalog come from different servers, each left
     * alone on its own when it pushes back
     */
    private val arrivalsGuard: ServiceGuard = ServiceGuard(),
    private val catalogGuard: ServiceGuard = ServiceGuard(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Monotonic nanoseconds, to age the answers. Dart's clock counts
     * microseconds; milliseconds would let an answer from the same
     * millisecond pass a maxAge of zero.
     */
    private val ticks: () -> Long = System::nanoTime,
    /** Where shared requests and housekeeping run, past any one caller */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val client = client.forService(REQUEST_TIMEOUT)

    /** An answer of the arrivals service and the point it was asked around */
    private class Area(
        val latitude: Double,
        val longitude: Double,
        /** When it came, in epoch milliseconds and in [ticks] */
        val at: Long,
        val tick: Long,
        val stops: List<NearbyStop>,
    )

    /**
     * Recent answers of the arrivals service, by the point asked around,
     * so a stop inside one asked about a moment ago needs no new request
     */
    private val recentAreas = ArrayDeque<Area>()

    /**
     * Requests on their way, so asking for the same point twice at once
     * shares one answer instead of sending two
     */
    private val inFlight = HashMap<String, Deferred<JsonElement>>()

    /**
     * Stop positions, from the routes or worked out earlier: a stop never
     * moves, so they are kept for good and spare the extra lookups. In
     * the order they were learned, the oldest first to go; the routes
     * that add to them come with the line screens.
     */
    private var positions: MutableMap<String, List<Double>>? = null
    private val positionsLock = Mutex()
    private val positionsSavePending = AtomicBoolean(false)

    init {
        if (pruned.compareAndSet(false, true)) {
            scope.launch { cache.prune(CACHE_LIFETIME) }
        }
    }

    /**
     * Stops within [radiusMeters] of a position, each with its next buses,
     * and placed on the map when its place is known.
     */
    suspend fun getNearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = MAX_RADIUS_METERS,
    ): List<NearbyStop> {
        val radius = if (radiusMeters > MAX_RADIUS_METERS) MAX_RADIUS_METERS else radiusMeters
        val url = "$ARRIVALS_URL?latitud=${dartDouble(latitude)}" +
            "&longitud=${dartDouble(longitude)}&radio=$radius"

        val decoded = getJson(url, arrivalsGuard)

        if (decoded is JsonObject) {
            val error = decoded["error"]
            if (error != null && error !is JsonNull) {
                throw ServerApiException("Arrivals service: ${error.dartString()}")
            }
        }
        if (decoded !is JsonArray) throw ServerApiException("Unexpected arrivals response")

        val known = knownPositions()
        val stops = decoded.filterIsInstance<JsonObject>().map { placed(parseStop(it), known) }

        // Whoever asked, the answer also serves trips planned nearby. The
        // Flutter app also learns how often each line comes here; that
        // memory comes with the trip planner.
        if (radius == MAX_RADIUS_METERS) {
            synchronized(recentAreas) {
                recentAreas.addLast(Area(latitude, longitude, now(), ticks(), stops))
                if (recentAreas.size > MAX_RECENT_AREAS) recentAreas.removeFirst()
            }
        }
        return stops
    }

    private fun placed(stop: NearbyStop, known: Map<String, List<Double>>): NearbyStop =
        when (val position = known[stop.id]) {
            null -> stop
            else -> stop.copy(latitude = position[0], longitude = position[1])
        }

    /**
     * Buses on their way to [stops], asking as little as possible: an area
     * asked about within [maxAge] is reused, and the rest are grouped so
     * one request answers for every stop within its reach.
     */
    suspend fun arrivalsFor(stops: List<LineStop>, maxAge: Duration = 1.minutes): LiveArrivals {
        fun covers(area: Area, stop: LineStop) =
            metersBetween(area.latitude, area.longitude, stop.latitude, stop.longitude) <=
                ARRIVALS_REACH_METERS

        val now = now()
        val tick = ticks()
        val recent = synchronized(recentAreas) { recentAreas.toList() }.asReversed()
        val areas = LinkedHashSet<Area>()
        val missing = mutableListOf<LineStop>()
        for (stop in stops) {
            val known = recent.firstOrNull {
                tick - it.tick <= maxAge.inWholeNanoseconds && covers(it, stop)
            }
            if (known != null) areas.add(known) else missing.add(stop)
        }

        val centres = areasCovering(
            missing.map { GeoPoint(it.latitude, it.longitude) },
            ARRIVALS_REACH_METERS,
        )

        val failed = AtomicBoolean(false)
        val answers = coroutineScope {
            centres.map { centre ->
                async {
                    try {
                        val found = getNearbyStops(centre.latitude, centre.longitude)
                        Area(centre.latitude, centre.longitude, now(), ticks(), found)
                    } catch (_: ApiException) {
                        failed.set(true)
                        null
                    }
                }
            }.awaitAll()
        }
        areas.addAll(answers.filterNotNull())

        val seen = LinkedHashMap<String, NearbyStop>()
        for (area in areas) {
            for (stop in area.stops) seen[stop.id] = stop
        }
        return LiveArrivals(
            fetchedAt = if (areas.isEmpty()) now else areas.minOf { it.at },
            stops = seen.values.toList(),
            failed = failed.get(),
        )
    }

    /** Where each of [stopIds] stands, for those whose place is known */
    suspend fun stopPositions(stopIds: Iterable<String>): Map<String, GeoPoint> {
        val known = knownPositions()
        return buildMap {
            for (id in stopIds) {
                known[id]?.let { put(id, GeoPoint(it[0], it[1])) }
            }
        }
    }

    /**
     * Stops around a point, each placed on the map when possible: first as
     * soon as the service answers, then again once they are placed.
     *
     * The service reports how far a stop is but never where it is. The
     * routes place every stop they call at, and earlier looks some more;
     * for a stop still unplaced the same area is queried from two extra
     * vantage points and the stop is trilaterated from the three
     * distances. Stops that fall out of range of the extra points simply
     * come back without a position.
     */
    fun locatedStops(latitude: Double, longitude: Double): Flow<List<NearbyStop>> = flow {
        val offset = VANTAGE_OFFSET_METERS
        val lonScale = METRES_PER_DEGREE * cos(latitude * PI / 180)

        val placed = getNearbyStops(latitude, longitude)
        // The list is useful before the stops are placed: hand it over now
        emit(placed)
        // Every stop here has been placed before: no extra requests needed
        if (placed.all { it.hasPosition }) return@flow

        val extras = try {
            coroutineScope {
                listOf(
                    async { getNearbyStops(latitude + offset / METRES_PER_DEGREE, longitude) },
                    async { getNearbyStops(latitude, longitude + offset / lonScale) },
                ).awaitAll()
            }
        } catch (_: ApiException) {
            // Placing the stops is a bonus: the list already went out
            return@flow
        }

        val north = extras[0].associate { it.id to it.distanceMeters }
        val east = extras[1].associate { it.id to it.distanceMeters }

        val located = placed.map { stop ->
            if (stop.hasPosition) return@map stop
            val dNorth = north[stop.id] ?: return@map stop
            val dEast = east[stop.id] ?: return@map stop

            val point = trilaterate(
                Reading(0.0, 0.0, stop.distanceMeters),
                Reading(0.0, offset, dNorth),
                Reading(offset, 0.0, dEast),
            ) ?: return@map stop

            stop.copy(
                latitude = latitude + point.second / METRES_PER_DEGREE,
                longitude = longitude + point.first / lonScale,
            )
        }

        rememberPositions(located)
        emit(located)
    }

    /** [locatedStops] once every stop that can be placed is */
    suspend fun getLocatedStops(latitude: Double, longitude: Double): List<NearbyStop> =
        locatedStops(latitude, longitude).last()

    private suspend fun knownPositions(): MutableMap<String, List<Double>> = positionsLock.withLock {
        positions ?: loadPositions().also { positions = it }
    }

    private suspend fun loadPositions(): MutableMap<String, List<Double>> {
        val loaded = Collections.synchronizedMap(LinkedHashMap<String, List<Double>>())
        val cached = cache.read(POSITIONS_KEY) as? JsonObject ?: return loaded
        for ((id, position) in cached) {
            if (position is JsonArray && position.size == 2 && position.all { it.isNum }) {
                loaded[id] = listOf(position[0].doubleOrNull()!!, position[1].doubleOrNull()!!)
            }
        }
        return loaded
    }

    /** Keep the stops placed by trilateration that were not known */
    private suspend fun rememberPositions(stops: List<NearbyStop>) {
        val positions = knownPositions()
        var added = false
        for (stop in stops) {
            if (!stop.hasPosition || positions.containsKey(stop.id)) continue
            positions[stop.id] = listOf(stop.latitude!!, stop.longitude!!)
            added = true
        }
        if (added) savePositionsSoon()
    }

    /**
     * Save the positions a moment after they change: a whole network of
     * routes comes in within a second or two, and a write or two does for
     * all of it
     */
    private fun savePositionsSoon() {
        if (!positionsSavePending.compareAndSet(false, true)) return
        scope.launch {
            delay(POSITIONS_SAVE_DELAY)
            positionsSavePending.set(false)
            val positions = knownPositions()
            val saved = synchronized(positions) {
                val iterator = positions.keys.iterator()
                while (positions.size > MAX_STORED_POSITIONS) {
                    iterator.next()
                    iterator.remove()
                }
                JsonObject(positions.mapValues { (_, at) -> JsonArray(at.map(::JsonPrimitive)) })
            }
            cache.write(POSITIONS_KEY, saved)
        }
    }

    /** A distance [d] read from ([x], [y]) in a local metric frame */
    private class Reading(val x: Double, val y: Double, val d: Double)

    /** Intersect three distance circles in a local metric frame: (x, y), or null when they cannot meet */
    private fun trilaterate(p1: Reading, p2: Reading, p3: Reading): Pair<Double, Double>? {
        val a = 2 * (p2.x - p1.x)
        val b = 2 * (p2.y - p1.y)
        val c = p1.d * p1.d - p2.d * p2.d - p1.x * p1.x + p2.x * p2.x - p1.y * p1.y + p2.y * p2.y
        val d = 2 * (p3.x - p2.x)
        val e = 2 * (p3.y - p2.y)
        val f = p2.d * p2.d - p3.d * p3.d - p2.x * p2.x + p3.x * p3.x - p2.y * p2.y + p3.y * p3.y

        val denominator = a * e - d * b
        if (abs(denominator) < 1e-9) return null

        return (c * e - f * b) / denominator to (a * f - d * c) / denominator
    }

    /** The full MIO station catalog, used to add favorites without GPS */
    suspend fun getStations(): List<Station> {
        val decoded = cachedJson("stations", STATIONS_URL, STATIONS_MAX_AGE)
        return decoded.filterIsInstance<JsonObject>()
            .filter { it["latitude"].isNum && it["longitude"].isNum }
            .map {
                Station(
                    id = it["id"].long().toInt(),
                    name = it["name"].dartString() ?: "",
                    address = it["address"].dartString() ?: "",
                    latitude = it["latitude"].double(),
                    longitude = it["longitude"].double(),
                )
            }
    }

    /** Lines serving a stop, useful when no bus is on its way */
    suspend fun getLinesByStop(stopId: String): List<StopLine> {
        val decoded = cachedJson("lines_$stopId", "$LINES_BY_STOP_URL/$stopId", LINES_MAX_AGE)
        return decoded.filterIsInstance<JsonObject>()
            .map { StopLine(shortName = it["shortName"].dartString() ?: "", name = it["name"].dartString() ?: "") }
            .filter { it.shortName.isNotEmpty() }
    }

    /**
     * A list from the catalog server, from the cache while it is fresh.
     * Past its age it is asked for again, and the old copy still answers
     * when that fails: the catalog changes a few times a year, and a day
     * offline should not lose it. The Flutter app's staleOk and remember
     * come with the routes, which alone use them.
     */
    private suspend fun cachedJson(key: String, url: String, maxAge: Duration): JsonArray {
        val entry = cache.readEntry(key)
        val kept = entry?.value as? JsonArray
        if (kept != null && now() - entry.savedAt <= maxAge.inWholeMilliseconds) return kept

        return try {
            fetchList(key, url)
        } catch (e: ApiException) {
            kept ?: throw e
        }
    }

    private suspend fun fetchList(key: String, url: String): JsonArray {
        val decoded = getJson(url, catalogGuard) as? JsonArray
            ?: throw ServerApiException("Unexpected catalog response")
        cache.write(key, decoded)
        return decoded
    }

    private fun parseStop(json: JsonObject): NearbyStop {
        val buses = json["buses"]
        val stopName = json["nombreParada"].dartString() ?: ""
        return NearbyStop(
            id = json["idParada"].dartString() ?: "",
            name = stopName,
            distanceMeters = json["distanciaMetros"].doubleOrNull() ?: 0.0,
            arrivals = if (buses is JsonArray) {
                buses.filterIsInstance<JsonObject>()
                    .filter { it["tiempoEstimadoDeSalida"].isNum }
                    .map { bus ->
                        BusArrival(
                            line = bus["nombreLinea"].dartString() ?: "",
                            destination = bus["nombreDestino"].dartString() ?: "",
                            arrivalTime = bus["tiempoEstimadoDeSalida"].long(),
                            vehicleId = bus["vehiculoId"].dartString() ?: "",
                            stopName = stopName,
                        )
                    }
            } else {
                emptyList()
            },
        )
    }

    /**
     * GET [url] and decode its JSON body, unless [guard] says the server
     * asked to be left alone; a server that pushes back arms it.
     */
    private suspend fun getJson(url: String, guard: ServiceGuard): JsonElement {
        val request = synchronized(inFlight) {
            inFlight.getOrPut(url) {
                scope.async(start = CoroutineStart.LAZY) { getJsonGuarded(url, guard) }.also { request ->
                    request.invokeOnCompletion {
                        synchronized(inFlight) {
                            if (inFlight[url] === request) inFlight.remove(url)
                        }
                    }
                }
            }
        }
        request.start()
        return request.await()
    }

    private suspend fun getJsonGuarded(url: String, guard: ServiceGuard): JsonElement {
        guard.check()
        try {
            val decoded = getJsonWithRetry(url)
            guard.answered()
            return decoded
        } catch (e: ApiException) {
            if (ServiceGuard.isPushBack(e)) guard.pushedBack()
            throw e
        }
    }

    /**
     * GET [url] and decode its JSON body, retrying transient failures once.
     * A rate limit is not retried: asking again at once only prolongs it.
     */
    private suspend fun getJsonWithRetry(url: String): JsonElement {
        var lastError: ApiException? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(backoff)
            try {
                val response = client.getText(url)
                response.checkStatus()
                return try {
                    parseJson(response.body)
                } catch (_: JsonFormatException) {
                    throw ServerApiException("Unexpected response format")
                }
            } catch (e: ApiException) {
                if (!e.isRetryable) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    companion object {
        const val ARRIVALS_URL =
            "https://servicios.siur.com.co/buscarutas/api/paradas_con_buses_proximos_a_llegar.php"

        private const val STATIONS_URL = "https://wsmio.siur.com.co:8083/apiMIO/jaxrs/stations"
        private const val LINES_BY_STOP_URL = "https://wsmio.siur.com.co:8083/apiMIO/jaxrs/linesByStop"

        /** The catalog changes a few times a year at most */
        private val STATIONS_MAX_AGE = 7.days

        /** Which lines serve a stop is stable too */
        private val LINES_MAX_AGE = 30.days

        /** The arrivals service rejects anything above this radius */
        const val MAX_RADIUS_METERS = 300

        val REQUEST_TIMEOUT = 10.seconds
        private const val MAX_ATTEMPTS = 2
        private const val MAX_RECENT_AREAS = 24
        private const val POSITIONS_KEY = "stop_positions"

        /**
         * The routes place a bit over two thousand stops, so this holds every
         * one the user could plausibly meet while never growing without end
         */
        private const val MAX_STORED_POSITIONS = 4000

        private val POSITIONS_SAVE_DELAY = 2.seconds

        /** Metres per degree of latitude, close enough over a few hundred metres */
        private const val METRES_PER_DEGREE = 111320.0

        /** How far north and east of the point the extra readings are taken */
        private const val VANTAGE_OFFSET_METERS = 120.0

        /** Files nobody has written in this long are swept once per run */
        private val CACHE_LIFETIME = 45.days

        private val pruned = AtomicBoolean(false)
    }
}
