package com.yenreh.caligo.ui.map

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/**
 * Until the map has drawn the tiles it asked for: [asked] holds, then the
 * map is at rest [QUIET_CHECKS] checks in a row. MapCompose decodes tiles
 * on threads of its own and paces its drawing with delays on the main
 * thread, whose clock moves here only when told: at rest, none of its
 * threads is at work and the main thread has nothing due, so nothing it
 * draws is still on the way.
 */
fun ComposeTestRule.waitForMapTiles(asked: () -> Boolean) {
    val main = shadowOf(Looper.getMainLooper())
    waitUntil(WAIT_TIMEOUT_MS) {
        main.idleFor(Duration.ofMillis(STEP_MS))
        asked()
    }
    var quiet = 0
    waitUntil(WAIT_TIMEOUT_MS) {
        main.idleFor(Duration.ofMillis(STEP_MS))
        waitForIdle()
        quiet = if (mapAtWork() || !main.isIdle) 0 else quiet + 1
        quiet >= QUIET_CHECKS
    }
}

/** Whether a thread other than this one runs MapCompose's code: a tile decoded, or the tiles in view worked out */
private fun mapAtWork(): Boolean = Thread.getAllStackTraces().any { (thread, frames) ->
    thread !== Thread.currentThread() && frames.any { it.className.startsWith(MAP_PACKAGE) }
}

private const val MAP_PACKAGE = "ovh.plrapps.mapcompose"

/** Virtual time the main thread moves on each check, past the map's own pacing */
private const val STEP_MS = 100L

/** Checks at rest in a row, so work handed to a thread that has not started on it yet is seen */
private const val QUIET_CHECKS = 5
