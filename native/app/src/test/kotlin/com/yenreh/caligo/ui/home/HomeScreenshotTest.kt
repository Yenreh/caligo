package com.yenreh.caligo.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.ui.cards.CardsState
import com.yenreh.caligo.ui.stops.StopsState
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The home at Pixel size (1080 x 2400 px, 420 dpi), in Spanish, light and
 * dark: with sample cards and stops, and empty
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class HomeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Cards and stops: the cards condensed

    @Test
    fun bothLight() = capture("home_both_light", HomeContent.BOTH, HomeSamples.cardsState, HomeSamples.stopsState)

    @Test
    @Config(qualifiers = "+night")
    fun bothDark() = capture("home_both_dark", HomeContent.BOTH, HomeSamples.cardsState, HomeSamples.stopsState)

    /** Each control named and 48 dp to touch at least */
    @Test
    fun controlsAreAccessible() {
        compose.setContent { CaliGoTheme { HomeScreen(HomeSamples.settings(HomeContent.BOTH), HomeSamples.cardsState, HomeSamples.stopsState) } }
        compose.assertControlsAccessible()
    }

    // At font scale 2.0, the largest Android offers: nothing clipped or overlapping

    @Test
    @Config(fontScale = 2f)
    fun bothFontScale2() = capture("home_both_font2", HomeContent.BOTH, HomeSamples.cardsState, HomeSamples.stopsState)

    @Test
    @Config(fontScale = 2f)
    fun cardsFontScale2() = capture("home_cards_font2", HomeContent.CARDS, HomeSamples.cardsState)

    // Cards alone, in full

    @Test
    fun cardsLight() = capture("home_cards_light", HomeContent.CARDS, HomeSamples.cardsState)

    @Test
    @Config(qualifiers = "+night")
    fun cardsDark() = capture("home_cards_dark", HomeContent.CARDS, HomeSamples.cardsState)

    // Stops alone

    @Test
    fun stopsLight() = capture("home_stops_light", HomeContent.STOPS, stops = HomeSamples.stopsState)

    @Test
    @Config(qualifiers = "+night")
    fun stopsDark() = capture("home_stops_dark", HomeContent.STOPS, stops = HomeSamples.stopsState)

    // Nothing saved

    @Test
    fun emptyCardsLight() = capture("home_empty_cards_light", HomeContent.CARDS)

    @Test
    @Config(qualifiers = "+night")
    fun emptyCardsDark() = capture("home_empty_cards_dark", HomeContent.CARDS)

    @Test
    fun emptyStopsLight() = capture("home_empty_stops_light", HomeContent.STOPS, stops = StopsState())

    @Test
    @Config(qualifiers = "+night")
    fun emptyStopsDark() = capture("home_empty_stops_dark", HomeContent.STOPS, stops = StopsState())

    private fun capture(
        name: String,
        content: HomeContent,
        cards: CardsState = CardsState(),
        stops: StopsState? = null,
    ) {
        compose.setContent { CaliGoTheme { HomeScreen(HomeSamples.settings(content), cards, stops) } }
        compose.captureWindow(name)
    }
}
