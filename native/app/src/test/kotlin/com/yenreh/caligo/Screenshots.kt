package com.yenreh.caligo

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.github.takahirom.roborazzi.captureRoboImage

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
