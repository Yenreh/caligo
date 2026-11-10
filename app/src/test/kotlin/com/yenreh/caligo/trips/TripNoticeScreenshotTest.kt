package com.yenreh.caligo.trips

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
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.trips.JourneyFixtures.DESTINATION
import com.yenreh.caligo.trips.JourneyFixtures.between
import com.yenreh.caligo.trips.JourneyFixtures.busOn
import com.yenreh.caligo.trips.JourneyFixtures.calle5Route
import com.yenreh.caligo.trips.JourneyFixtures.fixAt
import com.yenreh.caligo.trips.JourneyFixtures.option
import com.yenreh.caligo.trips.JourneyFixtures.origin
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.out
import com.yenreh.caligo.waits.Alerts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A planner trip's one notification in each phase, as the framework lays
 * it out, collapsed and expanded, light and dark, in English and Spanish,
 * under build/outputs/roborazzi/trip_notice_*.png: on foot to the stop,
 * waiting there with its two lines' buses, and on the bus. The shade
 * itself and the Live Update chip only show on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TripNoticeScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stop = GeoPoint(out[0].latitude, out[0].longitude)
    private val planned = Journey.planned(option, DESTINATION, NOW - 3 * MINUTE)
    private val buses = listOf(AwaitedLine("E21", NOW + 2 * MINUTE), AwaitedLine("T31", NOW + 5 * MINUTE))

    private val walking = planned.fixed(fixAt(between(origin, stop, 200.0), NOW))
    private val waiting = planned.fixed(fixAt(stop, NOW - MINUTE))
    private val riding = planned.took("T31", NOW - 2 * MINUTE)
        .fixed(fixAt(calle5Route.pointAt(900.0), NOW))
        .withBuses("T31", listOf(busOn(calle5Route, "41022", 930.0)), NOW)

    private fun notification(journey: Journey, near: Boolean): Notification {
        TripNotifications.createChannels(context)
        val words = TripWords(context.resources)
        val notice = TripNotice.of(journey, NOW, Alerts(), words, 15_000, awaited = if (journey.ride == null) buses else emptyList(), near = near, busAlertOn = false)
        return TripNotifications.build(context, journey.startedAt, notice, Alerts(), words)
    }

    // The views the shade inflates: off a device, the only way to lay one out
    @Suppress("DEPRECATION")
    private fun capture(name: String, journey: Journey, near: Boolean, dark: Boolean) {
        val builder = Notification.Builder.recoverBuilder(context, notification(journey, near))
        for ((suffix, views) in listOf("collapsed" to builder.createContentView(), "expanded" to builder.createBigContentView())) {
            val parent = FrameLayout(context)
            parent.addView(views.apply(context, parent))
            val width = 411 * 3
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            parent.layout(0, 0, width, parent.measuredHeight.coerceAtLeast(1))
            val bitmap = Bitmap.createBitmap(width, parent.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.WHITE)
            parent.draw(canvas)
            bitmap.captureRoboImage("build/outputs/roborazzi/trip_notice_${name}_$suffix.png")
        }
    }

    private fun all(language: String, dark: Boolean) {
        val tone = if (dark) "dark" else "light"
        capture("walk_${language}_$tone", walking, near = true, dark = dark)
        capture("wait_${language}_$tone", waiting, near = true, dark = dark)
        capture("ride_${language}_$tone", riding, near = false, dark = dark)
    }

    @Test
    fun englishLight() = all("en", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun englishDark() = all("en", dark = true)

    @Test
    @Config(qualifiers = "es")
    fun spanishLight() = all("es", dark = false)

    @Test
    @Config(qualifiers = "es-night")
    fun spanishDark() = all("es", dark = true)
}
