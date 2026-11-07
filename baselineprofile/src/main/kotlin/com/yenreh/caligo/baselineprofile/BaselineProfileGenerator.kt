package com.yenreh.caligo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The path the profile covers: a cold start to the home with its content,
 * the saved cards and stops (seeded as the README says), their balances
 * and arrivals, then a scroll through them.
 *
 * `./gradlew :app:generateBaselineProfile` with the phone connected.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val targetAppId: String =
        InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId not passed as an instrumentation argument")

    @Test
    fun startToTheHome() = rule.collect(packageName = targetAppId, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        // Loaded: a card, a stop, or the empty home
        device.wait(Until.hasObject(By.res(Pattern.compile("card|favorite_stop|home_empty"))), CONTENT_WAIT)
        // The arrivals, when there are stops to ask about
        if (device.hasObject(By.res("favorite_stop"))) {
            device.wait(Until.hasObject(By.res("arrival")), ARRIVALS_WAIT)
        }

        val list = device.findObject(By.res("home_list")) ?: return@collect
        // Keep the gesture off the system's back edges
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }

    private companion object {
        const val CONTENT_WAIT = 10_000L
        const val ARRIVALS_WAIT = 15_000L
    }
}
