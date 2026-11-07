package com.yenreh.caligo.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A theme change fades over 200 ms, as MaterialApp's AnimatedTheme does */
@RunWith(AndroidJUnit4::class)
class ThemeChangeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mixesEachChannelAsFlutterColorLerp() {
        val mid = lerpChannels(Color(0xFF000000), Color(0xFFFF8040), 0.5f)

        assertEquals(Color(0xFF804020), mid)
        assertSame(LabPalette.Light, LabPalette.Light.lerp(LabPalette.Dark, 0f))
        assertSame(LabPalette.Dark, LabPalette.Light.lerp(LabPalette.Dark, 1f))
    }

    @Test
    fun aChangeFadesOverTwoHundredMilliseconds() {
        var dark by mutableStateOf(false)
        var bg = Color.Unspecified
        compose.mainClock.autoAdvance = false
        compose.setContent { CaliGoTheme(darkTheme = dark) { bg = LabTheme.palette.bg } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(LabPalette.Light.bg, bg)

        dark = true
        Snapshot.sendApplyNotifications()
        // The frame that sees the change starts the fade
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        // Part of the way, neither end
        assertNotEquals(LabPalette.Light.bg, bg)
        assertNotEquals(LabPalette.Dark.bg, bg)

        compose.mainClock.advanceTimeBy(200)
        assertEquals(LabPalette.Dark.bg, bg)
    }
}
