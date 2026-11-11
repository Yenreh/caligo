package com.yenreh.caligo.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.location.LocationPermission
import com.yenreh.caligo.trips.FakeTripControl
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.stops.FakeNotificationAccess
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.trips.TookWaits
import com.yenreh.caligo.ui.trips.TripViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "I took it" opens the trip screen over the screen on show; the home leads back to the trip running */
@RunWith(AndroidJUnit4::class)
class OpenTripNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = ApplicationProvider.getApplicationContext<CaliGoApp>().container
    private val repository = FakeStopsRepository(emptyList(), emptyList()).apply { routes = mapOf("E21" to TripFixtures.route) }
    private val trips = FakeTripControl()
    private val waits = TookWaits()
    private val opened = Channel<Route.Trip>(Channel.CONFLATED)

    private fun show() {
        val flow = opened.receiveAsFlow()
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            AtTime(TripFixtures.NOW) {
                CaliGoTheme {
                    AppNavigation(
                        settings,
                        mapTiles = { androidx.compose.runtime.remember { com.yenreh.caligo.ui.map.FixtureTiles() } },
                        openedTrips = flow,
                        runningTrip = trips.running,
                        tripViewModel = { took ->
                            androidx.lifecycle.viewmodel.compose.viewModel {
                                TripViewModel(
                                    took,
                                    repository,
                                    trips,
                                    waits,
                                    FakeLocationSource(permission = LocationPermission.GRANTED),
                                    FakeNotificationAccess(granted = true),
                                    now = { TripFixtures.NOW },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(WAIT_TIMEOUT_MS) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun iTookItOpensWhereToGetOffThenTheHomeLeadsBack() {
        show()
        opened.trySend(Route.Trip(TripFixtures.took.key))
        waitFor("trip_choose")
        assertEquals(listOf(TripFixtures.took.waitKey), waits.took)
        compose.onNodeWithTag("trip_skip").performClick()
        waitFor("trip_running")
        // Back home, the trip is a tap away
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitFor("home_trip")
        compose.onNodeWithTag("home_trip").performClick()
        waitFor("trip_running")
        compose.onNode(hasText("Following the ride to Universidades")).assertExists()
    }
}
