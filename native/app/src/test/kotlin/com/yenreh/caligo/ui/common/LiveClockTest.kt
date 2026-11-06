package com.yenreh.caligo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.ui.TestLifecycleOwner
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** live_clock_test.dart */
@RunWith(AndroidJUnit4::class)
class LiveClockTest {
    @get:Rule
    val compose = createComposeRule()

    /** Counts its own compositions, recomposing on every beat of the clock above */
    @Composable
    private fun Watcher(builds: MutableList<Int>) {
        watchLiveClock()
        SideEffect { builds.add(1) }
    }

    @Test
    fun recomposesWhatWatchesItOnEveryBeat() {
        val builds = mutableListOf<Int>()
        compose.mainClock.autoAdvance = false
        compose.setContent { LiveClock(interval = 15.seconds) { Watcher(builds) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val before = builds.size

        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()

        assertEquals(before + 2, builds.size)
    }

    @Test
    fun staysStillWhileHiddenAndCatchesUpWhenShown() {
        val builds = mutableListOf<Int>()
        val screen = TestLifecycleOwner(Lifecycle.State.CREATED)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                LiveClock(interval = 15.seconds) { Watcher(builds) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val before = builds.size

        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(before, builds.size)

        compose.runOnUiThread { screen.moveTo(Lifecycle.State.RESUMED) }
        // A few frames: the state change, the beat, the recomposition
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(builds.size > before)
    }
}
