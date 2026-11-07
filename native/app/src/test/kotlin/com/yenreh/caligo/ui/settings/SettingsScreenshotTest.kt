package com.yenreh.caligo.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.data.datasources.AppRelease
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.licenses.LicenseCatalog
import com.yenreh.caligo.ui.licenses.LicenseScreen
import com.yenreh.caligo.ui.licenses.LicensesScreen
import com.yenreh.caligo.ui.licenses.licenseParagraphs
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings and licenses in Spanish, light and dark: at Pixel size (1080 x
 * 2400 px, 420 dpi) as they open, and the settings whole on a screen tall
 * enough to hold them, with an update found
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings = Settings(isLoading = false)
    private val state = SettingsState(cacheBytes = 187_392)
    private val found = state.copy(
        update = UpdateStatus.Available(
            AppRelease(
                version = "4.7.0",
                page = "https://github.com/Yenreh/CaliGo/releases/tag/v4.7.0",
                apk = "https://github.com/Yenreh/CaliGo/releases/download/v4.7.0/CaliGo-v4.7.0-arm64-v8a.apk",
            ),
        ),
    )

    private fun settings(name: String, state: SettingsState) {
        compose.setContent { CaliGoTheme { SettingsScreen(settings, state, appVersion = "0.1.0") } }
        compose.captureWindow(name)
    }

    @Test
    fun settingsLight() = settings("settings_light", state)

    @Test
    @Config(qualifiers = "+night")
    fun settingsDark() = settings("settings_dark", state)

    /** Each control named and 48 dp to touch at least */
    @Test
    @Config(qualifiers = TALL)
    fun controlsAreAccessible() {
        compose.setContent { CaliGoTheme { SettingsScreen(settings, found, appVersion = "0.1.0") } }
        compose.assertControlsAccessible()
    }

    // At font scale 2.0, the largest Android offers: nothing clipped or overlapping

    @Test
    @Config(fontScale = 2f)
    fun settingsFontScale2() = settings("settings_font2", state)

    @Test
    @Config(qualifiers = TALL)
    fun settingsWholeLight() = settings("settings_whole_light", found)

    @Test
    @Config(qualifiers = TALL_NIGHT)
    fun settingsWholeDark() = settings("settings_whole_dark", found)

    private val packages get() = runBlocking { LicenseCatalog.load(ApplicationProvider.getApplicationContext()) }

    private fun licenses(name: String) {
        val packages = packages
        compose.setContent { CaliGoTheme { LicensesScreen(packages, appVersion = "0.1.0", onBack = {}, onPackage = {}) } }
        compose.captureWindow(name)
    }

    @Test
    fun licensesLight() = licenses("licenses_light")

    @Test
    @Config(qualifiers = "+night")
    fun licensesDark() = licenses("licenses_dark")

    private fun license(name: String) {
        val app = packages.first()
        val paragraphs = app.licenses.map(::licenseParagraphs)
        compose.setContent { CaliGoTheme { LicenseScreen(app.name, app.licenses.size, paragraphs, onBack = {}) } }
        compose.captureWindow(name)
    }

    @Test
    fun licenseLight() = license("license_light")

    @Test
    @Config(qualifiers = "+night")
    fun licenseDark() = license("license_dark")

    private companion object {
        /** A Pixel 7 tall enough for the whole settings list */
        const val TALL = "es-w411dp-h2300dp-normal-long-notround-any-420dpi-keyshidden-nonav"
        const val TALL_NIGHT = "es-w411dp-h2300dp-normal-long-notround-any-night-420dpi-keyshidden-nonav"
    }
}
