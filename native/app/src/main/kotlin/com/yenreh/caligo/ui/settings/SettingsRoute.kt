package com.yenreh.caligo.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.ui.lab.LabTone
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The settings with their data: the view model, the system's document
 * picker for imports, links opened in the browser and the snackbars the
 * actions end with.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.screen.collectAsStateWithLifecycle()
    val snackbars = rememberLabSnackbars()
    val resources = LocalResources.current
    val links = LocalUriHandler.current
    // A copy of the file is not needed: the provider reads it once, now
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.import(it) }

    LaunchedEffect(viewModel, snackbars, resources) {
        viewModel.messages.collect { message ->
            snackbars.show(message.text(resources), message.tone, message.duration)
        }
    }

    SettingsScreen(
        settings = settings,
        state = state,
        appVersion = viewModel.appVersion,
        modifier = modifier,
        snackbars = snackbars,
        actions = SettingsActions(
            onBack = onBack,
            onThemeMode = viewModel::setThemeMode,
            onHomeContent = viewModel::setHomeContent,
            onSortByProximity = viewModel::setSortStopsByProximity,
            onDarkMap = viewModel::setDarkMap,
            onSharpMap = viewModel::setSharpMap,
            onRefreshBalancesOnOpen = viewModel::setRefreshBalancesOnOpen,
            onSaveFarePrice = viewModel::saveFarePrice,
            onExport = { viewModel.export() },
            onImport = {
                try {
                    picker.launch(arrayOf(AndroidBackupFiles.MIME_TYPE))
                } catch (_: ActivityNotFoundException) {
                    // No document picker on the phone: as a picker that fails
                    viewModel.importUnavailable()
                }
            },
            onClearCache = { viewModel.clearCache() },
            onCheckUpdates = { viewModel.checkForUpdates() },
            onDownload = { links.openOutside(it.apk ?: it.page) },
            onLicenses = onLicenses,
            onRepository = { links.openOutside(AppInfo.REPOSITORY) },
            onDataSource = { links.openOutside(AppInfo.DATA_SOURCE_URL) },
        ),
    )
}

/** In the browser or whatever app takes [url]; nothing when none does */
private fun UriHandler.openOutside(url: String) {
    try {
        openUri(url)
    } catch (_: IllegalArgumentException) {
        // No app for it: url_launcher's launchUrl gives up as quietly
    } catch (_: ActivityNotFoundException) {
        // The same, from older handlers
    }
}

private val SettingsMessage.tone: LabTone
    get() = when (this) {
        SettingsMessage.FarePriceUpdated, SettingsMessage.CacheCleared -> LabTone.OK
        SettingsMessage.NothingToExport -> LabTone.NEUTRAL
        SettingsMessage.ExportFailed -> LabTone.CRIT
        is SettingsMessage.Imported -> when {
            count < 0 -> LabTone.CRIT
            count == 0 -> LabTone.NEUTRAL
            else -> LabTone.OK
        }
    }

private val SettingsMessage.duration: Duration
    get() = if (this == SettingsMessage.FarePriceUpdated) 2.seconds else 4.seconds
