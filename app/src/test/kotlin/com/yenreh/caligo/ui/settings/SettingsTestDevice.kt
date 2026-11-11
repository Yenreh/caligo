package com.yenreh.caligo.ui.settings

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yenreh.caligo.data.Backup
import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.SettingsStore
import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CacheEntry
import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.CardRemoteDatasource
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.PlacesLocalDatasource
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.datasources.StopsRemoteDatasource
import com.yenreh.caligo.data.datasources.UpdateChecker
import com.yenreh.caligo.data.map.MapTileCache
import com.yenreh.caligo.data.repositories.CardRepositoryImpl
import com.yenreh.caligo.data.repositories.PlacesRepositoryImpl
import com.yenreh.caligo.data.repositories.StopsRepositoryImpl
import com.yenreh.caligo.domain.entities.CardEntity
import java.io.Closeable
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient

/**
 * What the settings screen stands on, for real but for the system's part:
 * a database in memory, settings in [folder], GitHub answering
 * [release], and files shared or picked through [files]
 */
class SettingsTestDevice(folder: File, release: () -> MockResponse = { MockService.status(404) }) : Closeable {
    /** Where imports, exports and saves run, as the app's own scope */
    val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = AppDatabase(ApplicationProvider.getApplicationContext(), name = null)
    val cards = CardRepositoryImpl(CardLocalDatasource(database), CardRemoteDatasource(OkHttpClient()))
    val stops = StopsRepositoryImpl(
        StopsLocalDatasource(database),
        StopsRemoteDatasource(OkHttpClient(), JsonCache.None),
        JsonCache.None,
    )
    val store = SettingsStore(File(folder, "settings.json"), work).also { runBlocking { it.loaded.join() } }
    val places = PlacesRepositoryImpl(PlacesLocalDatasource(database))
    val backup = Backup(database, cards, stops, store, places)
    val github = MockService { _, _ -> release() }
    val files = FakeBackupFiles()
    val cache = MemoryCache(bytes = 2048)

    /** The map tiles, empty until a test puts some there */
    val tiles = MapTileCache(File(folder, "fm_cache"))

    fun viewModel(appVersion: String = "4.1.0", files: BackupFiles = this.files) = SettingsViewModel(
        store = store,
        backup = backup,
        updates = UpdateChecker(github.client, abi = "arm64-v8a"),
        cache = cache,
        tiles = tiles,
        files = files,
        work = work,
        appVersion = appVersion,
    )

    override fun close() {
        work.cancel()
        github.close()
        database.close()
    }

    companion object {
        val card = CardEntity(
            internalId = "8d0e6f7a-1111-4c5e-9f00-000000000001",
            id = "06051868",
            prefix = "19",
            suffix = "981",
            name = "Mine",
            position = 0,
        )

        /** A Flutter backup with one card, one stop and the dark theme */
        val BACKUP = """
            {"version": 4, "exportDate": "2026-09-30T10:15:30.123",
             "cards": [{"internalId": "x", "id": "06051868", "prefix": "19", "suffix": "981",
                        "name": "Mine", "position": 0, "balance": 15300.0, "lastUpdate": null}],
             "favoriteStops": [{"id": "500800", "name": "Plaza de Cayzedo A1", "anchorLatitude": 3.4516,
                                "anchorLongitude": -76.532, "position": 0, "stopId": "500800"}],
             "settings": {"themeMode": 2, "farePrice": 3700}}
        """.trimIndent()
    }
}

/** Shares by keeping the text; reads what [documents] holds for a picked document */
class FakeBackupFiles : BackupFiles {
    val shared = mutableListOf<String>()
    val documents = mutableMapOf<Uri, String>()
    var failure: Exception? = null

    /** Holds a read until completed, when set */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun share(text: String) {
        failure?.let { throw it }
        shared.add(text)
    }

    override suspend fun read(uri: Uri): String {
        gate?.await()
        failure?.let { throw it }
        return documents[uri] ?: throw java.io.FileNotFoundException(uri.toString())
    }
}

/** A cache that only counts its size, and empties when cleared */
class MemoryCache(var bytes: Long) : JsonCache {
    var cleared = 0

    override suspend fun read(key: String, maxAge: Duration?): JsonElement? = null

    override suspend fun readEntry(key: String, remember: Boolean): CacheEntry? = null

    override suspend fun write(key: String, value: JsonElement, remember: Boolean) = Unit

    override suspend fun prune(maxAge: Duration) = Unit

    override suspend fun sizeInBytes() = bytes

    override suspend fun clear() {
        cleared++
        bytes = 0
    }
}
