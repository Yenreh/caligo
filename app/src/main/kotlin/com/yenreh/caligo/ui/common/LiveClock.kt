package com.yenreh.caligo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

private val LocalLiveClock = staticCompositionLocalOf<MutableIntState?> { null }

/**
 * What time it is for whatever the screens work out from it: countdowns,
 * "Updated" lines, whether a line runs now. The device's clock; tests stop
 * it at the moment their fixtures are built on.
 */
val LocalNow = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * Shared beat for the arrival countdowns in [content].
 *
 * Only what calls [watchLiveClock] recomposes on each beat, not the screen
 * around it. The beat stops while [content] cannot be seen, such as when
 * another screen covers it, and catches up the moment it shows again.
 */
@Composable
fun LiveClock(interval: Duration = 15.seconds, content: @Composable () -> Unit) {
    val beat = remember { mutableIntStateOf(0) }
    // Below STARTED once covered by another screen or the app is away
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = state.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(visible, interval) {
        while (visible) {
            beat.intValue++
            delay(interval)
        }
    }
    CompositionLocalProvider(LocalLiveClock provides beat, content = content)
}

/**
 * Recompose the caller on every beat; does nothing without a clock above.
 * Read-only, so the read counts for the caller's scope, not one of its own.
 */
@Composable
@ReadOnlyComposable
fun watchLiveClock(): Int = LocalLiveClock.current?.intValue ?: 0
