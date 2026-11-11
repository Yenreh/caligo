package com.yenreh.caligo

import android.content.Intent
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
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.navigation.AppNavigation
import com.yenreh.caligo.ui.stops.OpenStop
import com.yenreh.caligo.ui.stops.WaitBellsHost
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.trips.TripNotifications
import com.yenreh.caligo.ui.navigation.Route
import com.yenreh.caligo.ui.navigation.key
import com.yenreh.caligo.waits.WaitNotifications
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as CaliGoApp).container
        // Held until settings.json is read, so the first frame already has
        // the chosen theme and home content
        installSplashScreen().setKeepOnScreenCondition { container.settings.settings.value.isLoading }
        super.onCreate(savedInstanceState)
        // The location and notification permissions are asked from here, whatever screen wants them
        container.location.attach(this)
        container.notificationAccess.attach(this)
        // A wait's notification tapped: its stop, once, not again on a rotation
        if (savedInstanceState == null) {
            openedStop(intent)?.let(openedStops::trySend)
            openedTrip(intent)?.let(openedTrips::trySend)
        }
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
                WaitBellsHost(container.waits, container.notificationAccess) {
                    AppNavigation(
                        settings,
                        openedStops = openedStopsFlow,
                        openedTrips = openedTripsFlow,
                        runningTrip = container.trips.running,
                    )
                }
            }
        }
    }

    /** Stops to show, from a wait's notification tapped */
    private val openedStops = Channel<OpenStop>(Channel.CONFLATED)
    private val openedStopsFlow = openedStops.receiveAsFlow()

    /** Trip screens to show, from "I took it" or the trip's notification */
    private val openedTrips = Channel<Route.Trip>(Channel.CONFLATED)
    private val openedTripsFlow = openedTrips.receiveAsFlow()

    // One activity: a notification tapped while it runs comes here
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedStop(intent)?.let(openedStops::trySend)
        openedTrip(intent)?.let(openedTrips::trySend)
    }


    /** The stop a wait's notification opens, from [intent] */
    private fun openedStop(intent: Intent?): OpenStop? {
        if (intent?.action != WaitNotifications.ACTION_OPEN_STOP) return null
        val id = intent.getStringExtra(WaitNotifications.EXTRA_STOP_ID) ?: return null
        val placed = intent.hasExtra(WaitNotifications.EXTRA_LATITUDE)
        val stop = NearbyStop(
            id = id,
            name = intent.getStringExtra(WaitNotifications.EXTRA_STOP_NAME).orEmpty(),
            distanceMeters = 0.0,
            latitude = if (placed) intent.getDoubleExtra(WaitNotifications.EXTRA_LATITUDE, 0.0) else null,
            longitude = if (placed) intent.getDoubleExtra(WaitNotifications.EXTRA_LONGITUDE, 0.0) else null,
        )
        return OpenStop(
            stop,
            intent.getDoubleExtra(WaitNotifications.EXTRA_ANCHOR_LATITUDE, 0.0),
            intent.getDoubleExtra(WaitNotifications.EXTRA_ANCHOR_LONGITUDE, 0.0),
        )
    }

    companion object {
        /**
         * The trip screen [intent] opens: the ride "I took it" names, or the
         * trip running. None when relaunched from Recents with the old
         * intent, its ride long gone.
         */
        internal fun openedTrip(intent: Intent?): Route.Trip? {
            intent ?: return null
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            if (intent.action == TripNotifications.ACTION_OPEN_TRIP) return Route.Trip()
            return WaitNotifications.tookOf(intent)?.let { Route.Trip(it.key) }
        }

        /** enableEdgeToEdge's own scrims for three-button navigation */
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
