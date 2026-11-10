package com.yenreh.caligo.ui.trips

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripNotice
import com.yenreh.caligo.trips.TripNotifications
import com.yenreh.caligo.trips.TripWords
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.waits.Alerts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The trip screen at Pixel size in Spanish, choosing where to get off and
 * on the ride, light, dark and at font scale 2, and the trip's
 * notification as the framework lays it out, under
 * build/outputs/roborazzi/trip_*.png
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class TripScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Three minutes in, by the rider's own fixes, getting off at Pampalinda */
    private val riding = Trip.start(TripFixtures.plan, NOW - 3 * MINUTE).fixed(fix(1210.0, NOW))

    private fun shot(name: String, content: TripContent, tag: String) {
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(content = content, title = "E21 a Universidades", subtitle = "Desde Plaza de Cayzedo A1")
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        compose.captureWindow(name)
    }

    private fun choosing(name: String) = shot(name, TripContent.Choosing(TripFixtures.plan), "trip_choose")

    private fun running(name: String) = shot(name, TripContent.Running(riding), "trip_running")

    @Test
    fun chooseLight() = choosing("trip_choose_light")

    @Test
    @Config(qualifiers = "+night")
    fun chooseDark() = choosing("trip_choose_dark")

    @Test
    @Config(fontScale = 2f)
    fun chooseFontScale2() = choosing("trip_choose_font2")

    @Test
    fun rideLight() = running("trip_ride_light")

    @Test
    @Config(qualifiers = "+night")
    fun rideDark() = running("trip_ride_dark")

    @Test
    @Config(fontScale = 2f)
    fun rideFontScale2() = running("trip_ride_font2")

    // The notification, as the shade would inflate it off a device

    @Suppress("DEPRECATION")
    private fun notification(name: String, dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TripNotifications.createChannels(context)
        val words = TripWords(context.resources)
        val notice = TripNotice.of(riding, NOW, Alerts(), words, 15_000)
        val built: Notification = TripNotifications.build(context, riding.startedAt, notice, Alerts(), words)
        val builder = Notification.Builder.recoverBuilder(context, built)
        for ((suffix, views) in listOf("collapsed" to builder.createContentView(), "expanded" to builder.createBigContentView())) {
            val parent = FrameLayout(context)
            parent.addView(views.apply(context, parent))
            val width = 1080
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            parent.layout(0, 0, width, parent.measuredHeight.coerceAtLeast(1))
            val bitmap = Bitmap.createBitmap(width, parent.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.WHITE)
            parent.draw(canvas)
            bitmap.captureRoboImage("build/outputs/roborazzi/trip_notification_${name}_$suffix.png")
        }
    }

    @Test
    fun notificationLight() = notification("light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun notificationDark() = notification("dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun notificationFontScale2() = notification("font2", dark = false)
}
