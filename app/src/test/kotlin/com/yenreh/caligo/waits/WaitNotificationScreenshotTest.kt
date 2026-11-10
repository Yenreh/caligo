package com.yenreh.caligo.waits

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
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

    // The views the shade inflates: off a device, the only way to lay one out
    @Suppress("DEPRECATION")
    private fun capture(name: String, dark: Boolean) {
        val builder = Notification.Builder.recoverBuilder(context, notification())
        for ((suffix, views) in listOf("collapsed" to builder.createContentView(), "expanded" to builder.createBigContentView())) {
            val parent = FrameLayout(context)
            val view = views.apply(context, parent)
            parent.addView(view)
            val width = 411 * 3
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            parent.layout(0, 0, width, parent.measuredHeight.coerceAtLeast(1))
            val bitmap = Bitmap.createBitmap(width, parent.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.WHITE)
            parent.draw(canvas)
            bitmap.captureRoboImage("build/outputs/roborazzi/wait_notification_${name}_$suffix.png")
        }
    }

    @Test
    fun light() = capture("light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture("dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun fontScale2() = capture("font2", dark = false)
}
