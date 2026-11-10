package com.yenreh.caligo.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.assertControlsAccessible
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The home while a trip runs: a bar over the buttons leads back to it */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class HomeTripBarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val trip = Trip.start(TripFixtures.plan, HomeSamples.NOW)
    private var opened = 0

    private fun show() {
        compose.setContentAt(HomeSamples.NOW) {
            CaliGoTheme {
                HomeScreen(
                    HomeSamples.settings(HomeContent.BOTH),
                    HomeSamples.cardsState,
                    HomeSamples.stopsState,
                    actions = HomeActions(onTrip = { opened++ }),
                    trip = trip,
                )
            }
        }
    }

    @Test
    fun theBarSaysWhereTheRiderGetsOffAndOpensTheTrip() {
        show()
        compose.onNode(hasText("On the E21")).assertExists()
        compose.onNode(hasText("Get off at Pampalinda · ~10 min")).assertExists()
        compose.onNodeWithTag("home_trip").performClick()
        assertEquals(1, opened)
        compose.assertControlsAccessible()
    }

    @Test
    @Config(qualifiers = "+es")
    fun screenshot() {
        show()
        compose.captureWindow("home_trip_light")
    }
}
