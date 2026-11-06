package com.yenreh.caligo.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app's screens as MainActivity shows them, over the app's own container */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<CaliGoApp>().container

    @Before
    fun show() {
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            CaliGoTheme { AppNavigation(settings) }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { !exists(matcher) }

    private val settingsButton = hasContentDescription("Settings")
    private val settingsTitle = hasText("Settings")
    private val backArrow = hasContentDescription("Back")

    private fun openSettings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(settingsTitle)
    }

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun theHomeOpensSettingsAndBackComesHome() {
        openSettings()
        waitGone(settingsButton)

        systemBack()

        waitFor(settingsButton)
        waitGone(settingsTitle)
    }

    @Test
    fun theSettingsArrowComesHomeToo() {
        openSettings()

        compose.onNodeWithContentDescription("Back").performClick()

        waitFor(settingsButton)
        waitGone(backArrow)
    }

    @Test
    fun aPredictiveBackLeftHalfWayStaysAndOneLetGoGoesHome() {
        openSettings()
        val back = compose.activity.onBackPressedDispatcher

        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        // Half way, the home shows from under the settings
        waitFor(settingsButton)
        compose.onNodeWithText("Settings").assertExists()

        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        waitGone(settingsButton)
        compose.onNodeWithText("Settings").assertExists()

        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(300f, 500f, 0.8f, BackEventCompat.EDGE_LEFT))
            back.onBackPressed()
        }

        waitFor(settingsButton)
        waitGone(settingsTitle)
    }

    @Test
    fun theHomeFollowsTheSettingsAsTheyChange() {
        waitFor(hasText("No cards"))
        openSettings()

        compose.onNodeWithText("Favorite stops only").performScrollTo().performClick()
        systemBack()

        waitFor(hasText("No saved stops"))
        assertEquals(HomeContent.STOPS, container.settings.settings.value.homeContent)
    }

    @Test
    fun theLicensesListEveryPackageAndOpenOne() {
        openSettings()
        compose.onNodeWithText("Open source licenses").performScrollTo().performClick()
        waitFor(hasText("Licenses"))

        val app = hasText("CaliGo") and hasText("1 license")
        waitFor(app)
        compose.onNode(app).performClick()

        waitFor(hasText("CaliGo Copyright (C) 2026 Yenreh", substring = true))
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertExists()

        systemBack()
        waitFor(app)
        systemBack()
        waitFor(settingsTitle)
        systemBack()
        waitFor(settingsButton)
    }
}
