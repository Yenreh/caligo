package com.yenreh.caligo.ui.stops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.yenreh.caligo.ui.common.rememberShownOnScreen

/**
 * Arrivals on their pace while the screen calling it is in view: no
 * polling the service in the background or under another screen. Back
 * in view, whatever changed meanwhile is asked for. [homeOnly] is the
 * home, which asks only for the stops it lists.
 */
@Composable
fun WatchArrivals(viewModel: StopsViewModel?, homeOnly: Boolean = false) {
    val shown = rememberShownOnScreen()
    DisposableEffect(viewModel, shown, homeOnly) {
        val watcher = Any()
        val watched = viewModel?.takeIf { shown }
        watched?.startAutoRefresh(watcher, homeOnly = homeOnly)
        watched?.refreshArrivals()
        onDispose { watched?.stopAutoRefresh(watcher) }
    }
}
