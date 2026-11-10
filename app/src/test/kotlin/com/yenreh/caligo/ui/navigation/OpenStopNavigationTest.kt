package com.yenreh.caligo.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.NearbyStop
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.OpenStop
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A wait's notification tapped: its stop's details open over the screen on show, asked about afresh */
@RunWith(AndroidJUnit4::class)
class OpenStopNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun opensTheStopsDetailsWithItsBuses() {
        val container = ApplicationProvider.getApplicationContext<CaliGoApp>().container
        val bus = BusArrival("T31", "Universidades", HomeSamples.NOW + 5 * 60_000, "637001")
        val repository = FakeStopsRepository(
            favorites = emptyList(),
            nearby = listOf(NearbyStop("500800", "Plaza de Cayzedo A1", 40.0, listOf(bus))),
        )
        ViewModelProvider.create(
            compose.activity,
            viewModelFactory { initializer { StopsViewModel(repository, settings = container.settings.settings) } },
        )[StopsViewModel::class]
        val opened = Channel<OpenStop>(Channel.CONFLATED)
        val flow = opened.receiveAsFlow()
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            AtTime(HomeSamples.NOW) {
                CaliGoTheme { AppNavigation(settings, mapTiles = { remember { FixtureTiles() } }, openedStops = flow) }
            }
        }
        val place = GeoPoint(3.4517, -76.5321)
        opened.trySend(OpenStop(NearbyStop("500800", "Plaza de Cayzedo A1", 0.0, latitude = place.latitude, longitude = place.longitude), 3.4516, -76.532))
        compose.waitUntil(WAIT_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("stop_details"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) {
            compose.onAllNodes(hasText("Universidades"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Asked around the stop's own place
        assertEquals(place, repository.askedAround.last())
    }
}
