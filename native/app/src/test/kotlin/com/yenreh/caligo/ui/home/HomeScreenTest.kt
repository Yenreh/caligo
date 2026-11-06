package com.yenreh.caligo.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensOnTheEmptyHome() {
        compose.onNodeWithText("CaliGo").assertIsDisplayed()
        compose.onNodeWithText("No cards").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es")
    fun speaksSpanish() {
        compose.onNodeWithText("Sin tarjetas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Configuración").assertIsDisplayed()
    }
}
