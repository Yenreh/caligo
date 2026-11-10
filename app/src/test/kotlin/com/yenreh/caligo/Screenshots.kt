package com.yenreh.caligo

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.graphics.drawable.toBitmap
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage

/**
 * Draws the activity's window, background included, to
 * build/outputs/roborazzi/[name].png.
 *
 * A software draw of the decor view rather than Roborazzi's own capture:
 * that one goes through PixelCopy, which in a JVM that has run a few
 * activities before returns the window background alone.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.captureWindow(name: String) {
    waitForIdle()
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(bitmap)) }
    bitmap.captureRoboImage("build/outputs/roborazzi/$name.png")
}

/**
 * Draws every window on screen, the dialogs and sheets over the activity
 * included, to build/outputs/roborazzi/[name].png
 */
@OptIn(ExperimentalRoborazziApi::class)
fun AndroidComposeTestRule<*, out ComponentActivity>.captureScreen(name: String) {
    waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/$name.png")
}

/**
 * Lays [notification] out as the shade inflates it, collapsed and
 * expanded, [width] pixels wide on the shade's light or [dark] colour, to
 * build/outputs/roborazzi/[name]_collapsed.png and _expanded.png.
 *
 * The small icon goes in as the bitmap its drawable draws: given a
 * resource, the shade's icon view first tries ImageDecoder on it, which
 * fails on a vector anyway, and Robolectric's native decoder at times
 * crashes the JVM doing so.
 */
@Suppress("DEPRECATION")
fun captureNotification(context: Context, notification: Notification, name: String, width: Int, dark: Boolean) {
    val builder = Notification.Builder.recoverBuilder(context, notification)
    val small = checkNotNull(notification.smallIcon.loadDrawable(context))
    builder.setSmallIcon(Icon.createWithBitmap(small.toBitmap()))
    for ((suffix, views) in listOf("collapsed" to builder.createContentView(), "expanded" to builder.createBigContentView())) {
        val parent = FrameLayout(context)
        parent.addView(views.apply(context, parent))
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val height = parent.measuredHeight.coerceAtLeast(1)
        parent.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.WHITE)
        parent.draw(canvas)
        bitmap.captureRoboImage("build/outputs/roborazzi/${name}_$suffix.png")
    }
}
