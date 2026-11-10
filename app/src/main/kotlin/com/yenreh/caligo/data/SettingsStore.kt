package com.yenreh.caligo.data

import com.yenreh.caligo.data.datasources.writeAtomically
import com.yenreh.caligo.data.json.obj
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.data.json.settingsFromJson
import com.yenreh.caligo.data.json.toJson
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * The settings, in [file]: the Flutter app's `settings.json`, the same
 * compact JSON. It starts on the defaults, loading, and reads the file at
 * once; a file that cannot be read leaves the defaults.
 *
 * File access runs on [scope], one step at a time, so a save never races
 * the first read or another save.
 */
class SettingsStore(private val file: File, private val scope: CoroutineScope) {
    private val state = MutableStateFlow(Settings())
    private val fileLock = Mutex()

    val settings: StateFlow<Settings> = state.asStateFlow()

    /** The first read of the file */
    val loaded: Job = scope.launch {
        fileLock.withLock {
            state.value = try {
                if (file.exists()) {
                    settingsFromJson(parseJson(file.readText()).obj())
                } else {
                    state.value.copy(isLoading = false)
                }
            } catch (_: Exception) {
                state.value.copy(isLoading = false)
            }
        }
    }

    private suspend fun save() = fileLock.withLock {
        try {
            writeAtomically(file, state.value.toJson().toString())
        } catch (_: Exception) {
            // Silent, as in the Flutter app: the settings still apply
        }
    }

    private fun set(change: (Settings) -> Settings) {
        state.update(change)
        scope.launch { save() }
    }

    fun setThemeMode(mode: AppThemeMode) = set { it.copy(themeMode = mode) }

    /** Ignored unless [price] is above zero */
    fun setFarePrice(price: Int) {
        if (price > 0) set { it.copy(farePrice = price) }
    }

    fun setHomeContent(content: HomeContent) = set { it.copy(homeContent = content) }

    fun setRefreshBalancesOnOpen(enabled: Boolean) = set { it.copy(refreshBalancesOnOpen = enabled) }

    fun setDarkMap(enabled: Boolean) = set { it.copy(darkMap = enabled) }

    fun setSharpMap(enabled: Boolean) = set { it.copy(sharpMap = enabled) }

    fun setSortStopsByProximity(enabled: Boolean) = set { it.copy(sortStopsByProximity = enabled) }

    fun setAlertSound(enabled: Boolean) = set { it.copy(alertSound = enabled) }

    fun setAlertVibrate(enabled: Boolean) = set { it.copy(alertVibrate = enabled) }

    /**
     * Apply the settings found in a backup, defaults for what it lacks.
     * Throws, changing nothing, when a value has the wrong type.
     */
    suspend fun importSettings(json: JsonObject) = applyImported(settingsFromJson(json))

    /** Apply settings read from a backup, and save them */
    suspend fun applyImported(imported: Settings) {
        // After the first read, which would otherwise undo it
        loaded.join()
        state.value = imported.copy(isLoading = false)
        save()
    }
}
