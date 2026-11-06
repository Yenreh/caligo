package com.yenreh.caligo.ui.home

import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.FakeCardRepository
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.TestLifecycleOwner
import com.yenreh.caligo.ui.cards.CardsViewModel
import com.yenreh.caligo.ui.favorite
import com.yenreh.caligo.ui.nearbyStop
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** What the Flutter MainScreen decides beyond drawing */
@RunWith(AndroidJUnit4::class)
class HomeRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val cards = FakeCardRepository(HomeSamples.cards)
    private val stops = FakeStopsRepository(
        favorites = listOf(favorite("a", 3.4516, -76.5320)),
        nearby = listOf(nearbyStop("a")),
    )

    /** The gap between refreshes runs on the looper's clock, which the test moves */
    private fun stopsViewModel() = StopsViewModel(stops, now = { SystemClock.uptimeMillis() })

    private fun show(
        settings: Settings,
        stopsViewModel: () -> StopsViewModel = { error("The home asked for the stops") },
        screen: TestLifecycleOwner = TestLifecycleOwner(),
    ) {
        val cardsViewModel = CardsViewModel(cards)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                CaliGoTheme {
                    HomeRoute(settings, cardsViewModel = cardsViewModel, stopsViewModel = { stopsViewModel() })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aHomeOfCardsNeverLoadsTheStops() {
        show(HomeSamples.settings(HomeContent.CARDS))
    }

    @Test
    fun refreshesEveryBalanceOnceAsTheAppOpens() {
        show(HomeSamples.settings(HomeContent.CARDS))

        assertEquals(listOf("06051868", "06051869"), cards.asked)
    }

    @Test
    fun leavesTheBalancesAloneWhenTheSettingsSaySo() {
        show(HomeSamples.settings(HomeContent.CARDS).copy(refreshBalancesOnOpen = false))

        assertTrue(cards.asked.isEmpty())
    }

    @Test
    fun leavesTheBalancesAloneWhenTheHomeShowsNoCards() {
        val model = stopsViewModel()
        show(HomeSamples.settings(HomeContent.STOPS), { model })

        assertTrue(cards.asked.isEmpty())
    }

    @Test
    fun waitsForTheSettingsBeforeRefreshing() {
        show(Settings(isLoading = true))

        assertTrue(cards.asked.isEmpty())
    }

    @Test
    fun followsTheArrivalsOnlyWhileInView() {
        val screen = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val model = stopsViewModel()
        show(HomeSamples.settings(HomeContent.STOPS), { model }, screen)
        // Loaded and asked once; coming into view right after is too soon
        assertEquals(1, stops.nearbyCalls)

        // Another screen covers it: nothing more, however long
        compose.runOnUiThread { screen.moveTo(Lifecycle.State.STARTED) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertEquals(1, stops.nearbyCalls)

        // Back in view: whatever changed meanwhile is asked for, then on pace
        compose.runOnUiThread { screen.moveTo(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        assertEquals(2, stops.nearbyCalls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2).plusSeconds(1))
        assertEquals(3, stops.nearbyCalls)
    }

    @Test
    fun comingBackFromAnotherScreenNeverRefreshesAgain() {
        // Opened with the refresh off; turned on in the settings, then home
        val cardsViewModel = CardsViewModel(cards)
        var settings by mutableStateOf(HomeSamples.settings(HomeContent.CARDS).copy(refreshBalancesOnOpen = false))
        var onScreen by mutableStateOf(true)
        compose.setContent {
            CaliGoTheme {
                if (onScreen) HomeRoute(settings, cardsViewModel = cardsViewModel, stopsViewModel = { error("No stops") })
            }
        }
        compose.waitForIdle()

        onScreen = false
        compose.waitForIdle()
        settings = settings.copy(refreshBalancesOnOpen = true)
        onScreen = true
        compose.waitForIdle()

        assertTrue(cards.asked.isEmpty())
    }
}
