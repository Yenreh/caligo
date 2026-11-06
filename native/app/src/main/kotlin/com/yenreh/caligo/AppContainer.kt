package com.yenreh.caligo

import android.content.Context
import com.yenreh.caligo.data.Backup
import com.yenreh.caligo.data.SettingsStore
import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.CardRemoteDatasource
import com.yenreh.caligo.data.datasources.FileJsonCache
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.datasources.StopsRemoteDatasource
import com.yenreh.caligo.data.repositories.CardRepositoryImpl
import com.yenreh.caligo.data.repositories.StopsRepositoryImpl
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
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
 * Files sit where the Flutter app kept them, so the final port, under
 * its package name, reads that app's data as is: `caligo.db` among the
 * databases, `settings.json` and the cache in `app_flutter`.
 */
class AppContainer(context: Context) {
    /** Outlives every screen: shared requests, saves, housekeeping */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** path_provider's documents directory on Android */
    private val documents: File = context.getDir("flutter", Context.MODE_PRIVATE)

    private val database = AppDatabase(context)
    private val http = OkHttpClient()
    private val cache: JsonCache = FileJsonCache(File(documents, "cache"))

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

    val backup = Backup(cardRepository, stopsRepository, settings)

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
