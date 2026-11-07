package com.yenreh.caligo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.ui.navigation.AppNavigation
import com.yenreh.caligo.ui.theme.CaliGoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as CaliGoApp).container
        // Held until settings.json is read, so the first frame already has
        // the chosen theme and home content
        installSplashScreen().setKeepOnScreenCondition { container.settings.settings.value.isLoading }
        super.onCreate(savedInstanceState)
        // The location permission is asked from here, whatever screen wants it
        container.location.attach(this)
        enableEdgeToEdge()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            // Bar icons that contrast with the app's paper, whatever the
            // system's own mode
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            CaliGoTheme(darkTheme = dark) {
                AppNavigation(settings)
            }
        }
    }

    private companion object {
        /** enableEdgeToEdge's own scrims for three-button navigation */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
