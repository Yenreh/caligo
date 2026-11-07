package com.yenreh.caligo

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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
