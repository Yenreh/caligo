package com.yenreh.caligo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Whether the screen calling it can be seen: the app in front and no
 * other screen covering it, which is when polling a service is worth it.
 *
 * Resumed, as Flutter's resumed state and uncovered route together: the
 * activity pauses when it leaves the front, and a screen another one
 * covers is held below resumed by its navigation entry.
 */
@Composable
fun rememberShownOnScreen(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}
