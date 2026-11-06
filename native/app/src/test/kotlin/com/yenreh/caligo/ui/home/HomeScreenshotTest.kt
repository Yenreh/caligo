package com.yenreh.caligo.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The empty home at Pixel size (1080 x 2400 px, 420 dpi), light and dark */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class HomeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeLight() = capture("home_light")

    @Test
    @Config(qualifiers = "+night")
    fun homeDark() = capture("home_dark")

    private fun capture(name: String) {
        compose.setContent { CaliGoTheme { HomeScreen() } }
        compose.captureWindow(name)
    }
}
