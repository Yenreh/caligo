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

    /** The T31 and the E21 at one stop, three minutes in and coming, the E21 passed and asking, or six and a half minutes in with the E21 alerting */
    private fun group(name: String, dark: Boolean, phase: String) {
        WaitNotifications.createChannels(context)
        val words = WaitWords(context.resources)
        val e21 = WaitFixtures.bus("E21", "e21", NOW + 8 * MINUTE)
        val t31 = Wait.start(awaited(WaitFixtures.t31), NOW)
        var e21Wait = Wait.start(awaited(e21), NOW)
        var at = NOW + 6 * MINUTE + 30_000
        if (phase == "coming") at = NOW + 3 * MINUTE
        if (phase == "asking" || phase == "alone_asking") {
            // Listed until a minute before its time, then gone: at the stop, then passed
            e21Wait = e21Wait.fed(WaitFixtures.answer(NOW + 6 * MINUTE, e21)).fed(WaitFixtures.answer(NOW + 7 * MINUTE + 30_000))
            at = NOW + 9 * MINUTE + 15_000
        }
        // The E21's wait alone, asking as a group does
        val waits = if (phase == "alone_asking") listOf(e21Wait) else listOf(t31, e21Wait)
        val notice = WaitNotice.ofGroup(waits, at, Alerts(), words, 15_000)
        if (phase == "alerting") check(notice.alerting)
        captureNotification(context, WaitNotifications.build(context, WaitNotifications.FIRST_ID, waits, notice, Alerts(), words), "wait_group_${phase}_$name", 411 * 3, dark)
    }

    private fun groups(name: String, dark: Boolean) {
        group(name, dark, "coming")
        group(name, dark, "asking")
        group(name, dark, "alerting")
        group(name, dark, "alone_asking")
    }

    @Test
    fun groupLight() = groups("es_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun groupDark() = groups("es_dark", dark = true)

    @Test
    @Config(qualifiers = "+en")
    fun groupEnglish() = groups("en_light", dark = false)

    @Test
    @Config(qualifiers = "+en-night")
    fun groupEnglishDark() = groups("en_dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun groupFontScale2() = groups("es_font2", dark = false)

    @Test
    fun light() = capture("light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture("dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun fontScale2() = capture("font2", dark = false)
}
