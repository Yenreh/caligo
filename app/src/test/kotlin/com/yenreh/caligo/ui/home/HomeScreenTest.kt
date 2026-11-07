package com.yenreh.caligo.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.MainActivity
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app as it opens, on a device with nothing saved */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The cards load off the main thread */
    private fun waitFor(text: String) = compose.waitUntil(WAIT_TIMEOUT_MS) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun opensOnTheEmptyHome() {
        waitFor("No cards")
        compose.onNodeWithText("CaliGo").assertIsDisplayed()
        compose.onNodeWithText("No cards").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find stops").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es")
    fun speaksSpanish() {
        waitFor("Sin tarjetas")
        compose.onNodeWithText("Sin tarjetas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Configuración").assertIsDisplayed()
        compose.onNodeWithContentDescription("Planear viaje").assertIsDisplayed()
    }
}
