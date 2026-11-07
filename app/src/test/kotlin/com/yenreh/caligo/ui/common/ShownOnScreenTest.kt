package com.yenreh.caligo.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.ui.TestLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** shown_on_screen_test.dart */
@RunWith(AndroidJUnit4::class)
class ShownOnScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hearsWhenAnotherScreenCoversItAndWhenItLeaves() {
        val heard = mutableListOf<Boolean>()
        // The screen's navigation entry, which a screen on top of it holds
        // below resumed
        val entry = TestLifecycleOwner(Lifecycle.State.RESUMED)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                val shown = rememberShownOnScreen()
                LaunchedEffect(shown) { heard.add(shown) }
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(true), heard)

        compose.runOnUiThread { entry.moveTo(Lifecycle.State.STARTED) }
        compose.waitForIdle()
        assertEquals(listOf(true, false), heard)

        compose.runOnUiThread { entry.moveTo(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        assertEquals(listOf(true, false, true), heard)
    }

    @Test
    fun hearsWhenTheAppGoesToTheBackgroundAndComesBack() {
        val heard = mutableListOf<Boolean>()
        compose.setContent {
            val shown = rememberShownOnScreen()
            LaunchedEffect(shown) { heard.add(shown) }
        }
        compose.waitForIdle()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertEquals(listOf(true, false), heard)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(listOf(true, false, true), heard)
    }
}
