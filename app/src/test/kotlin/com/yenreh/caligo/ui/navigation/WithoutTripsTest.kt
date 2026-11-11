package com.yenreh.caligo.ui.navigation

import android.app.NotificationManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.AppFeatures
import com.yenreh.caligo.BuildConfig
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.trips.TripActionReceiver
import com.yenreh.caligo.trips.TripChannel
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripNotifications
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.settings.SettingsScreen
import com.yenreh.caligo.ui.settings.SettingsState
import com.yenreh.caligo.ui.settings.SettingsViewModel
import com.yenreh.caligo.domain.entities.Settings
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.trips.PlanTripViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A build without trips, as 5.2.0 ships: no trip is reached from anywhere */
@RunWith(AndroidJUnit4::class)
class WithoutTripsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<CaliGoApp>()
    private val container = app.container
    private val opened = Channel<Route.Trip>(Channel.CONFLATED)
    private lateinit var backStack: NavBackStack<NavKey>

    init {
        // So in a build with trips too
        container.features.trips = false
    }

    private fun show(vararg restored: NavKey) {
        val flow = opened.receiveAsFlow()
        // A trip running, as a build with trips would have
        val running = MutableStateFlow<Journey?>(Journey.single(TripFixtures.plan, TripFixtures.NOW))
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            AtTime(TripFixtures.NOW) {
                CaliGoTheme {
                    AppNavigation(
                        settings,
                        backStack = rememberNavBackStack(*restored).also { backStack = it },
                        mapTiles = { androidx.compose.runtime.remember { com.yenreh.caligo.ui.map.FixtureTiles() } },
                        openedTrips = flow,
                        runningTrip = running,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theSwitchFollowsTheBuildAndNamesItsVersion() {
        assertEquals(BuildConfig.TRIPS, AppFeatures().trips)
        assertEquals(BuildConfig.TRIPS, BuildConfig.VERSION_NAME.endsWith("+trips"))
    }

    @Test
    fun theHomeHasNoTripBarAndAnIntentForATripScreenStaysHome() {
        show(Route.Home)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("home_trip")).fetchSemanticsNodes().isEmpty())
        opened.trySend(Route.Trip(TripFixtures.took.key))
        compose.waitForIdle()
        assertEquals(listOf<NavKey>(Route.Home), backStack.toList())
    }

    @Test
    fun aBackStackRestoredWithATripScreenClosesIt() {
        show(Route.Home, Route.Trip())
        assertEquals(listOf<NavKey>(Route.Home), backStack.toList())
    }

    @Test
    fun thePlannerOffersNoStartTrip() {
        val extras = MutableCreationExtras().apply { set(APPLICATION_KEY, app) }
        val lines = LinesViewModel.Factory.create(LinesViewModel::class.java, extras)
        val planner = PlanTripViewModel.factory(lines, null).create(PlanTripViewModel::class.java, extras)
        assertFalse(planner.canStartTrips)
    }

    @Test
    fun aTripStartRunsNoServiceAndMakesNoChannel() {
        container.trips.start(TripFixtures.plan)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(container.trips.running.value)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertNull(manager.getNotificationChannel(TripChannel.TRIPS.id))
    }

    @Test
    fun theAlertsSayNothingOfTrips() {
        val extras = MutableCreationExtras().apply { set(APPLICATION_KEY, app) }
        val model = SettingsViewModel.Factory.create(SettingsViewModel::class.java, extras)
        assertFalse(model.trips)
        compose.setContent {
            CaliGoTheme { SettingsScreen(Settings(isLoading = false), SettingsState(), appVersion = "5.2.0", trips = model.trips) }
        }
        compose.onNode(hasText("As an awaited bus comes within about 2 minutes. Off, the alert is silent"), useUnmergedTree = true)
            .performScrollTo().assertExists()
        compose.onNode(hasText("With the same alert"), useUnmergedTree = true).assertExists()
        assertTrue(compose.onAllNodes(hasText("trip", substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theTripNotificationsActionsDoNothing() {
        val manager = app.getSystemService(NotificationManager::class.java)
        for (action in listOf(TripNotifications.ACTION_FINISH, TripNotifications.ACTION_DISMISSED, TripNotifications.ACTION_UNDO)) {
            TripActionReceiver().onReceive(app, Intent(action).putExtra(TripNotifications.EXTRA_STARTED_AT, TripFixtures.NOW))
        }
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(shadowOf(manager).activeNotifications.isEmpty())
    }
}
