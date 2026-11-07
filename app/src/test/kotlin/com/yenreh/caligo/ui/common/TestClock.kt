package com.yenreh.caligo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [content] with the screens' clock stopped at [now], the moment the
 * fixtures are built on: countdowns and "Updated" lines then read the same
 * however long the run takes
 */
@Composable
fun AtTime(now: Long, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNow provides { now }, content = content)
}

/** "HH:mm" of [epochMillis], as the details sheet's "Updated" line writes it */
fun hourMinuteText(epochMillis: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(epochMillis))

/** [content] on screen with the screens' clock stopped at [now], as [AtTime] */
fun ComposeContentTestRule.setContentAt(now: Long, content: @Composable () -> Unit) {
    setContent { AtTime(now, content) }
}
