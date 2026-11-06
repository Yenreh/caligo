package com.yenreh.caligo.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stopsOnlyHomeAsksForStops() {
        compose.setContent { CaliGoTheme { HomeScreen(content = HomeContent.STOPS) } }
        compose.onNodeWithText("No saved stops").assertIsDisplayed()
    }

    @Test
    fun bothShowTheCardsHint() {
        compose.setContent { CaliGoTheme { HomeScreen(content = HomeContent.BOTH) } }
        compose.onNodeWithText("No cards").assertIsDisplayed()
    }
}
