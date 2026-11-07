package com.yenreh.caligo.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.BuildConfig
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.data.Backup
import com.yenreh.caligo.data.SettingsStore
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.data.datasources.AppRelease
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.UpdateChecker
import com.yenreh.caligo.data.json.dartIntTryParse
import com.yenreh.caligo.data.map.MapTileCache
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the last update check found; null in [SettingsState] until the user asks */
sealed interface UpdateStatus {
    data object NoReleases : UpdateStatus

    data class Available(val release: AppRelease) : UpdateStatus

    data class UpToDate(val version: String) : UpdateStatus

    data object Failed : UpdateStatus
}

/** The settings screen's own state, beside the settings themselves */
data class SettingsState(
    val checkingUpdates: Boolean = false,
    val update: UpdateStatus? = null,
    /** What the cache takes on disk */
    val cacheBytes: Long = 0,
)

/** What the screen reports with a snackbar */
sealed interface SettingsMessage {
    data object FarePriceUpdated : SettingsMessage

    data object CacheCleared : SettingsMessage

    data object NothingToExport : SettingsMessage

    /** The backup could not be written or shared; the Flutter app said there was nothing to export */
    data object ExportFailed : SettingsMessage

    /** [count] cards and stops added; 0 also when no file was picked, -1 for a failure */
    data class Imported(val count: Int) : SettingsMessage
}

/**
 * The Flutter SettingsScreen's state and actions. Imports and exports run
 * on [work], which outlives the screen, so leaving it never cuts one in
 * half; only their report is lost, as the Flutter screen drops it once
 * unmounted.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val backup: Backup,
    private val updates: UpdateChecker,
    private val cache: JsonCache,
    /** The map tiles, cached apart from the rest but counted with it */
    private val tiles: MapTileCache?,
    private val files: BackupFiles,
    private val work: CoroutineScope,
    /** The installed version, as the about section shows it */
    val appVersion: String,
) : ViewModel() {
    val settings: StateFlow<Settings> = store.settings

    private val state = MutableStateFlow(SettingsState())
    val screen: StateFlow<SettingsState> = state.asStateFlow()

    private val reports = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = reports.receiveAsFlow()

    init {
        viewModelScope.launch { loadCacheSize() }
    }

    fun setThemeMode(mode: AppThemeMode) = store.setThemeMode(mode)

    fun setHomeContent(content: HomeContent) = store.setHomeContent(content)

    fun setSortStopsByProximity(enabled: Boolean) = store.setSortStopsByProximity(enabled)

    fun setDarkMap(enabled: Boolean) = store.setDarkMap(enabled)

    fun setSharpMap(enabled: Boolean) = store.setSharpMap(enabled)

    fun setRefreshBalancesOnOpen(enabled: Boolean) = store.setRefreshBalancesOnOpen(enabled)

    /** Save [text] as the fare when it is a whole number above zero; anything else is ignored */
    fun saveFarePrice(text: String) {
        val price = dartIntTryParse(text) ?: return
        if (price <= 0 || price > Int.MAX_VALUE) return
        store.setFarePrice(price.toInt())
        reports.trySend(SettingsMessage.FarePriceUpdated)
    }

    private suspend fun loadCacheSize() {
        // Map tiles are cached apart from the rest, but count as cached data
        val bytes = coroutineScope {
            val tileBytes = async(Dispatchers.IO) { tiles?.sizeInBytes() ?: 0 }
            cache.sizeInBytes() + tileBytes.await()
        }
        state.update { it.copy(cacheBytes = bytes) }
    }

    fun clearCache(): Job = viewModelScope.launch {
        coroutineScope {
            launch { cache.clear() }
            launch(Dispatchers.IO) { tiles?.clear() }
        }
        loadCacheSize()
        reports.send(SettingsMessage.CacheCleared)
    }

    /** Only on request: nothing is asked of GitHub in the background */
    fun checkForUpdates(): Job {
        state.update { it.copy(checkingUpdates = true) }
        return viewModelScope.launch {
            val result = try {
                val release = updates.latest()
                when {
                    release == null -> UpdateStatus.NoReleases
                    UpdateChecker.isNewer(release.version, appVersion) -> UpdateStatus.Available(release)
                    else -> UpdateStatus.UpToDate(appVersion)
                }
            } catch (_: ApiException) {
                UpdateStatus.Failed
            }
            state.update { it.copy(checkingUpdates = false, update = result) }
        }
    }

    /** Share a backup of everything saved; nothing to share, or a failure, is reported */
    fun export(): Job = viewModelScope.launch {
        val failure = work.async {
            try {
                val text = backup.export() ?: return@async SettingsMessage.NothingToExport
                files.share(text)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SettingsMessage.ExportFailed
            }
        }.await()
        failure?.let { reports.send(it) }
    }

    /**
     * Restore the backup at [uri], the document the user picked; null when
     * the picker was left without one, which reports nothing new
     */
    fun import(uri: Uri?): Job = viewModelScope.launch {
        val count = if (uri == null) {
            0
        } else {
            work.async {
                try {
                    backup.import(files.read(uri))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    -1
                }
            }.await()
        }
        reports.send(SettingsMessage.Imported(count))
    }

    /** The phone has no document picker to open: an import that failed */
    fun importUnavailable() {
        reports.trySend(SettingsMessage.Imported(-1))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CaliGoApp
                val container = app.container
                SettingsViewModel(
                    store = container.settings,
                    backup = container.backup,
                    updates = container.updates,
                    cache = container.cache,
                    tiles = container.tileCache,
                    files = AndroidBackupFiles(app),
                    work = container.scope,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
        }
    }
}
