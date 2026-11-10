package com.yenreh.caligo.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.R
import com.yenreh.caligo.data.datasources.AppRelease
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.lab.LabChip
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabOutlinedButton
import com.yenreh.caligo.ui.lab.LabPanel
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabSwitch
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import java.util.Locale

/** What the settings screen does when touched */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onThemeMode: (AppThemeMode) -> Unit = {},
    val onHomeContent: (HomeContent) -> Unit = {},
    val onSortByProximity: (Boolean) -> Unit = {},
    val onDarkMap: (Boolean) -> Unit = {},
    val onSharpMap: (Boolean) -> Unit = {},
    val onRefreshBalancesOnOpen: (Boolean) -> Unit = {},
    val onAlertSound: (Boolean) -> Unit = {},
    val onAlertVibrate: (Boolean) -> Unit = {},
    val onSaveFarePrice: (String) -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
    /** The APK built for this phone, or the release page when there is none */
    val onDownload: (AppRelease) -> Unit = {},
    val onLicenses: () -> Unit = {},
    val onRepository: () -> Unit = {},
    val onDataSource: () -> Unit = {},
)

/** Settings, as the Flutter SettingsScreen draws them */
@Composable
fun SettingsScreen(
    settings: Settings,
    state: SettingsState,
    appVersion: String,
    modifier: Modifier = Modifier,
    actions: SettingsActions = SettingsActions(),
    snackbars: LabSnackbars = rememberLabSnackbars(),
    farePrice: TextFieldState = rememberTextFieldState(settings.farePrice.toString()),
) {
    val p = LabTheme.palette
    // A fare set elsewhere, as an import does, replaces what the field shows;
    // the Flutter field kept the one it opened with. Only a change counts, so
    // a fare typed and not saved yet stays through a rotation.
    var fareShown by rememberSaveable { mutableIntStateOf(settings.farePrice) }
    LaunchedEffect(farePrice, settings.farePrice) {
        if (settings.farePrice == fareShown) return@LaunchedEffect
        fareShown = settings.farePrice
        farePrice.setTextAndPlaceCursorAtEnd(settings.farePrice.toString())
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = { LabTopBar(stringResource(R.string.settings), onBack = actions.onBack) },
        snackbarHost = { LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 32.dp)
                .testTag("settings_list"),
        ) {
            Section(stringResource(R.string.appearance)) {
                ChoiceTile(
                    title = stringResource(R.string.theme_system),
                    subtitle = stringResource(R.string.theme_system_desc),
                    icon = painterResource(R.drawable.ic_brightness_auto),
                    selected = settings.themeMode == AppThemeMode.SYSTEM,
                    onClick = { actions.onThemeMode(AppThemeMode.SYSTEM) },
                )
                LabDivider()
                ChoiceTile(
                    title = stringResource(R.string.theme_light),
                    subtitle = stringResource(R.string.theme_light_desc),
                    icon = painterResource(R.drawable.ic_light_mode),
                    selected = settings.themeMode == AppThemeMode.LIGHT,
                    onClick = { actions.onThemeMode(AppThemeMode.LIGHT) },
                )
                LabDivider()
                ChoiceTile(
                    title = stringResource(R.string.theme_dark),
                    subtitle = stringResource(R.string.theme_dark_desc),
                    icon = painterResource(R.drawable.ic_dark_mode),
                    selected = settings.themeMode == AppThemeMode.DARK,
                    onClick = { actions.onThemeMode(AppThemeMode.DARK) },
                )
            }

            Section(stringResource(R.string.home_screen)) {
                val described = stringResource(R.string.home_screen_desc)
                ChoiceTile(
                    title = stringResource(R.string.home_cards_only),
                    subtitle = described,
                    icon = painterResource(R.drawable.ic_credit_card),
                    selected = settings.homeContent == HomeContent.CARDS,
                    onClick = { actions.onHomeContent(HomeContent.CARDS) },
                )
                LabDivider()
                ChoiceTile(
                    title = stringResource(R.string.home_stops_only),
                    subtitle = described,
                    icon = painterResource(R.drawable.ic_signpost),
                    selected = settings.homeContent == HomeContent.STOPS,
                    onClick = { actions.onHomeContent(HomeContent.STOPS) },
                )
                LabDivider()
                ChoiceTile(
                    title = stringResource(R.string.home_both),
                    subtitle = described,
                    icon = painterResource(R.drawable.ic_dashboard),
                    selected = settings.homeContent == HomeContent.BOTH,
                    onClick = { actions.onHomeContent(HomeContent.BOTH) },
                )
            }

            Section(stringResource(R.string.favorite_stops)) {
                SwitchTile(
                    title = stringResource(R.string.sort_by_proximity),
                    subtitle = stringResource(R.string.sort_by_proximity_desc),
                    icon = painterResource(R.drawable.ic_near_me),
                    checked = settings.sortStopsByProximity,
                    onChange = actions.onSortByProximity,
                )
            }

            Section(stringResource(R.string.map_tab)) {
                SwitchTile(
                    title = stringResource(R.string.dark_map),
                    subtitle = stringResource(R.string.dark_map_desc),
                    icon = painterResource(R.drawable.ic_map),
                    checked = settings.darkMap,
                    onChange = actions.onDarkMap,
                )
                LabDivider()
                SwitchTile(
                    title = stringResource(R.string.sharp_map),
                    subtitle = stringResource(R.string.sharp_map_desc),
                    icon = painterResource(R.drawable.ic_hd),
                    checked = settings.sharpMap,
                    onChange = actions.onSharpMap,
                )
            }

            Section(stringResource(R.string.alerts_section)) {
                SwitchTile(
                    title = stringResource(R.string.alert_sound),
                    subtitle = stringResource(R.string.alert_sound_desc),
                    icon = painterResource(R.drawable.ic_volume_up),
                    checked = settings.alertSound,
                    onChange = actions.onAlertSound,
                )
                LabDivider()
                SwitchTile(
                    title = stringResource(R.string.alert_vibrate),
                    subtitle = stringResource(R.string.alert_vibrate_desc),
                    icon = painterResource(R.drawable.ic_vibration),
                    checked = settings.alertVibrate,
                    onChange = actions.onAlertVibrate,
                )
            }

            Section(stringResource(R.string.my_cards)) {
                SwitchTile(
                    title = stringResource(R.string.refresh_balances_on_open),
                    subtitle = stringResource(R.string.refresh_balances_on_open_desc),
                    icon = painterResource(R.drawable.ic_sync),
                    checked = settings.refreshBalancesOnOpen,
                    onChange = actions.onRefreshBalancesOnOpen,
                )
            }

            Section(stringResource(R.string.fare)) {
                FarePrice(farePrice, onSave = { actions.onSaveFarePrice(farePrice.text.toString()) })
            }

            Section(stringResource(R.string.data)) {
                ActionTile(
                    title = stringResource(R.string.export_data),
                    subtitle = stringResource(R.string.export_data_desc),
                    icon = painterResource(R.drawable.ic_upload),
                    onClick = actions.onExport,
                )
                LabDivider()
                ActionTile(
                    title = stringResource(R.string.import_data),
                    subtitle = stringResource(R.string.import_data_desc),
                    icon = painterResource(R.drawable.ic_download),
                    onClick = actions.onImport,
                )
                LabDivider()
                ActionTile(
                    title = stringResource(R.string.cache),
                    subtitle = stringResource(R.string.cache_desc),
                    icon = painterResource(R.drawable.ic_cleaning_services),
                    onClick = actions.onClearCache,
                    trailing = { LabChip(formatBytes(state.cacheBytes)) },
                )
            }

            Section(stringResource(R.string.about), last = true) {
                About(state, appVersion, actions)
            }
        }
    }
}

@Composable
private fun Section(title: String, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    LabSectionLabel(title, Modifier.padding(start = 2.dp, bottom = 8.dp))
    LabPanel(
        modifier = if (last) Modifier else Modifier.padding(bottom = 24.dp),
        padding = PaddingValues(0.dp),
        content = content,
    )
}

/** One of a set: the icon and the square mark in accent when chosen */
@Composable
private fun ChoiceTile(title: String, subtitle: String, icon: Painter, selected: Boolean, onClick: () -> Unit) {
    val p = LabTheme.palette
    LabListTile(
        title = title,
        subtitle = subtitle,
        leading = icon,
        leadingTint = if (selected) p.accent else p.muted,
        titleWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        trailing = { SquareRadio(selected) },
    )
}

/** Square radio mark: an empty box, filled with accent when chosen */
@Composable
private fun SquareRadio(selected: Boolean) {
    val p = LabTheme.palette
    Box(
        Modifier
            .size(18.dp)
            .border(1.dp, if (selected) p.accent else p.rule)
            .padding(4.dp),
    ) {
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(p.accent),
            )
        }
    }
}

/** A setting on or off; the whole row flips it */
@Composable
private fun SwitchTile(title: String, subtitle: String, icon: Painter, checked: Boolean, onChange: (Boolean) -> Unit) {
    LabListTile(
        title = title,
        subtitle = subtitle,
        leading = icon,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { LabSwitch(checked) },
    )
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: Painter,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LabTheme.palette
    LabListTile(
        title = title,
        subtitle = subtitle,
        leading = icon,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        trailing = trailing ?: {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = p.muted)
        },
    )
}

@Composable
private fun FarePrice(state: TextFieldState, onSave: () -> Unit) {
    val p = LabTheme.palette
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.fare_price), style = MaterialTheme.typography.titleMedium.copy(color = p.ink))
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.fare_price_desc), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabTextField(
                state = state,
                style = LabText.mono(p, size = 16f),
                hint = "${Settings.DEFAULT_FARE_PRICE}",
                prefix = "$ ",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag("fare_price"),
            )
            Spacer(Modifier.width(8.dp))
            LabFilledButton(onClick = onSave, modifier = Modifier.height(50.dp)) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun About(state: SettingsState, appVersion: String, actions: SettingsActions) {
    val p = LabTheme.palette
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_directions_bus),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = p.accent,
            )
            Spacer(Modifier.width(10.dp))
            Text(AppInfo.NAME, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (appVersion.isNotEmpty()) LabChip("v$appVersion")
        }
        Spacer(Modifier.height(12.dp))
        UpdateCheck(state, actions)
        Spacer(Modifier.height(12.dp))
        // Not the operator's app, and the data is the operator's: both said
        // plainly, with the link Metro Cali asks for
        Text(stringResource(R.string.unofficial_notice), style = LabText.statusLine(p))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.data_from, AppInfo.DATA_SOURCE),
            modifier = Modifier
                .clickable(role = Role.Button, onClick = actions.onDataSource)
                .padding(vertical = 6.dp),
            style = LabText.mono(p, size = 12f, color = p.accent),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clickable(role = Role.Button, onClick = actions.onLicenses),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.licenses),
                    style = MaterialTheme.typography.labelLarge.copy(color = p.accent),
                )
            }
            Spacer(Modifier.weight(1f))
            RepoLink(actions.onRepository)
        }
    }
}

/**
 * The button that asks for the latest release, what it found, and the way
 * to download a newer one: the browser fetches the APK, and Android
 * installs it over this one from the download, so the app needs no
 * permission
 */
@Composable
private fun UpdateCheck(state: SettingsState, actions: SettingsActions) {
    val p = LabTheme.palette
    LabOutlinedButton(onClick = actions.onCheckUpdates, enabled = !state.checkingUpdates) {
        if (state.checkingUpdates) {
            CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_system_update_alt), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.check_updates))
    }
    val found = state.update ?: return
    val (text, color) = when (found) {
        UpdateStatus.NoReleases -> stringResource(R.string.no_releases_yet) to null
        is UpdateStatus.Available -> stringResource(R.string.update_available, found.release.version) to p.ok
        is UpdateStatus.UpToDate -> stringResource(R.string.up_to_date, found.version) to null
        UpdateStatus.Failed -> stringResource(R.string.update_check_failed) to p.crit
    }
    Spacer(Modifier.height(8.dp))
    Text(text, style = LabText.statusLine(p).let { if (color != null) it.copy(color = color) else it })
    if (found is UpdateStatus.Available) {
        Spacer(Modifier.height(8.dp))
        LabFilledButton(onClick = { actions.onDownload(found.release) }) {
            Icon(painterResource(R.drawable.ic_download_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.download_update, found.release.version))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** The footer link of the browser extensions: GitHub's mark and the author, opening the repository */
@Composable
private fun RepoLink(onClick: () -> Unit) {
    val p = LabTheme.palette
    LabTooltip(stringResource(R.string.author_link)) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_github_mark), contentDescription = null, tint = p.muted)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.made_by), style = LabText.mono(p, size = 12f, color = p.muted))
        }
    }
}

/** KB below a megabyte, MB with one decimal above, as the Flutter screen writes it */
internal fun formatBytes(bytes: Long): String {
    val mb = 1024 * 1024
    return if (bytes < mb) {
        String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    } else {
        String.format(Locale.ROOT, "%.1f MB", bytes / mb.toDouble())
    }
}

/** Snackbar wording of this message */
internal fun SettingsMessage.text(resources: Resources): String = when (this) {
    SettingsMessage.FarePriceUpdated -> resources.getString(R.string.fare_price_updated)
    SettingsMessage.CacheCleared -> resources.getString(R.string.cache_cleared)
    SettingsMessage.NothingToExport -> resources.getString(R.string.nothing_to_export)
    SettingsMessage.ExportFailed -> resources.getString(R.string.export_error)
    is SettingsMessage.Imported -> when {
        count < 0 -> resources.getString(R.string.import_error)
        count == 0 -> resources.getString(R.string.no_new_items_imported)
        else -> resources.getQuantityString(R.plurals.imported_items, count, count)
    }
}
