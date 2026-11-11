package com.yenreh.caligo

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.graphics.drawable.toBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import org.robolectric.Shadows.shadowOf

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
 * Lets a press's ripple run out before a capture. It plays on the frame
 * clock and the platform's animators, which move here only when told: the
 * waits before move them by as many steps as their polls happen to take.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.settlePress() {
    mainClock.advanceTimeBy(PRESS_SETTLE_MS)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PRESS_SETTLE_MS))
    waitForIdle()
}

private const val PRESS_SETTLE_MS = 3_000L

/**
 * Draws every window on screen, the dialogs and sheets over the activity
 * included, to build/outputs/roborazzi/[name].png: each a software draw,
 * as [captureWindow] and for the same reason, in the order they stack,
 * each window that dims what is behind it dimming it as the system would.
 * Roborazzi's own capture of the screen at times drew the activity under a
 * sheet as its background alone, and a ripple as far as real time had
 * taken it.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.captureScreen(name: String) {
    waitForIdle()
    val screen = activity.window.decorView
    val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
    runOnUiThread {
        val canvas = Canvas(bitmap)
        for (view in windowRoots()) {
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
            }
            // Where the system places it: by its gravity, as a dialog centred
            val at = Rect()
            Gravity.apply(params?.gravity ?: Gravity.NO_GRAVITY, view.width, view.height, Rect(0, 0, bitmap.width, bitmap.height), params?.x ?: 0, params?.y ?: 0, at)
            canvas.save()
            canvas.translate(at.left.toFloat(), at.top.toFloat())
            view.draw(canvas)
            canvas.restore()
        }
    }
    bitmap.captureRoboImage("build/outputs/roborazzi/$name.png")
}

/** Every window's root view, in the order they were added: the activity's, then the dialogs and sheets over it */
private fun windowRoots(): List<View> {
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getMethod("getInstance").invoke(null)
    val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
    return views.filterIsInstance<View>().filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
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
