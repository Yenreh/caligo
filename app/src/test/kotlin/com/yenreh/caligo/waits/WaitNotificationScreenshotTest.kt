package com.yenreh.caligo.waits

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.captureNotification
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.waits.WaitFixtures.MINUTE
import com.yenreh.caligo.waits.WaitFixtures.NOW
import com.yenreh.caligo.waits.WaitFixtures.awaited
import com.yenreh.caligo.waits.WaitFixtures.t31
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A wait's notification as the framework lays it out, collapsed and
 * expanded, at font scale 1 and 2, under
 * build/outputs/roborazzi/wait_notification_*.png. The system's own
 * shade and the Live Update chip only show on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
class WaitNotificationScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun notification(): Notification {
        WaitNotifications.createChannels(context)
        val words = WaitWords(context.resources)
        val wait = Wait.start(awaited(t31), NOW)
        val notice = WaitNotice.of(wait, NOW + 6 * MINUTE, Alerts(), words, 15_000)
        return WaitNotifications.build(context, WaitNotifications.FIRST_ID, wait, notice, Alerts(), words)
    }

    private fun capture(name: String, dark: Boolean) =
        captureNotification(context, notification(), "wait_notification_$name", 411 * 3, dark)

    @Test
    fun light() = capture("light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture("dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun fontScale2() = capture("font2", dark = false)
}
