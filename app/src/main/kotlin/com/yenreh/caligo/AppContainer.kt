package com.yenreh.caligo

import android.content.Context
import com.yenreh.caligo.data.Backup
import com.yenreh.caligo.data.SettingsStore
import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.AndroidGeocoder
import com.yenreh.caligo.data.datasources.CardRemoteDatasource
import com.yenreh.caligo.data.datasources.DeviceGeocoder
import com.yenreh.caligo.data.datasources.FileJsonCache
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.PlacesLocalDatasource
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.datasources.StopsRemoteDatasource
import com.yenreh.caligo.data.datasources.UpdateChecker
import com.yenreh.caligo.data.location.AndroidLocationSource
import com.yenreh.caligo.data.location.AndroidLocationUpdates
import com.yenreh.caligo.data.map.CartoFallback
import com.yenreh.caligo.data.map.MapTileCache
import com.yenreh.caligo.data.map.MapTileLoader
import com.yenreh.caligo.data.repositories.CardRepositoryImpl
import com.yenreh.caligo.data.repositories.PlacesRepositoryImpl
import com.yenreh.caligo.data.repositories.StopsRepositoryImpl
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.PlacesRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.trips.RepositoryTripSource
import com.yenreh.caligo.trips.TripDesk
import com.yenreh.caligo.ui.trips.TransitNetworkLoader
import com.yenreh.caligo.waits.Alerts
import com.yenreh.caligo.waits.AndroidNotificationAccess
import com.yenreh.caligo.waits.RepositoryWaitSource
import com.yenreh.caligo.waits.WaitDesk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Everything below the screens, made once per process and wired by hand.
 *
 * Files sit where the Flutter app kept them, so installed over it, under
 * the same package name, the app reads that data as is: `caligo.db` among
 * the databases, `settings.json` and the cache in `app_flutter`.
 */
class AppContainer(private val context: Context) {
    /** What the build turns on: trips or not */
    val features = AppFeatures()

    /** Outlives every screen: shared requests, saves, housekeeping */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** path_provider's documents directory on Android */
    private val documents: File = context.getDir("flutter", Context.MODE_PRIVATE)

    private val database = AppDatabase(context)
    private val http = OkHttpClient()
    val cache: JsonCache = FileJsonCache(File(documents, "cache"))

    val settings = SettingsStore(File(documents, "settings.json"), scope)

    val cardRepository: CardRepository = CardRepositoryImpl(
        CardLocalDatasource(database),
        CardRemoteDatasource(http),
    )

    val stopsRepository: StopsRepository = StopsRepositoryImpl(
        StopsLocalDatasource(database),
        StopsRemoteDatasource(http, cache, scope = scope),
        cache,
    )

    /** The places saved for quick plans in the planner */
    val placesRepository: PlacesRepository = PlacesRepositoryImpl(PlacesLocalDatasource(database))

    /** Every route as one network, for the trip planner: loaded once a run, when first needed */
    val transitNetwork by lazy { TransitNetworkLoader(stopsRepository, scope) }

    /** Android's own geocoder, for addresses and places typed in the planner */
    val geocoder by lazy { DeviceGeocoder(AndroidGeocoder(context)) }

    /** The map tiles, where flutter_map kept them: path_provider's cache directory */
    val tileCache = MapTileCache(File(context.cacheDir, MapTileCache.DIRECTORY))

    /** Made when a map first shows */
    val tileLoader by lazy { MapTileLoader(http, tileCache) }

    /** Whether the maps draw CARTO's tiles or, while it refuses them, OpenStreetMap's */
    val cartoFallback = CartoFallback()

    /** The device location, asked from the activity on screen */
    val location = AndroidLocationSource(context)

    /** The buses waited for, which the wait service follows */
    val waits = WaitDesk(context, RepositoryWaitSource(stopsRepository)).apply {
        alerts = { settings.settings.value.let { Alerts(sound = it.alertSound, vibrate = it.alertVibrate) } }
    }

    /** The ride followed, which the trip service follows apart from the waits */
    val trips = TripDesk(context, RepositoryTripSource(stopsRepository), AndroidLocationUpdates(context), scope).apply {
        alerts = { settings.settings.value.let { Alerts(sound = it.alertSound, vibrate = it.alertVibrate) } }
        enabled = { features.trips }
    }

    /** Whether a wait's notification may show, asked from the activity on screen */
    val notificationAccess = AndroidNotificationAccess(context)

    val backup = Backup(database, cardRepository, stopsRepository, settings, placesRepository)

    /** Asked only when the user says so, from the settings */
    val updates by lazy { UpdateChecker(http) }

    /**
     * Work the first screen waits for before reading any data: the debug
     * seed, when there is one
     */
    @Volatile
    var startup: Job = Job().apply { complete() }
        private set

    fun beforeFirstScreen(work: suspend () -> Unit) {
        startup = scope.launch { work() }
    }
}
