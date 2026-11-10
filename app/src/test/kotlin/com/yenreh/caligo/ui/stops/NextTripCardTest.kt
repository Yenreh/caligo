package com.yenreh.caligo.ui.stops

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.PhoneSamples
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.ui.common.MovingClock
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A favorite's card on the answers the phone sampled at stop 514208: the
 * A47 that reached the stop as its vehicle was listed for its next trip
 * fades out on a row of its own, until four minutes past its time
 */
@RunWith(AndroidJUnit4::class)
class NextTripCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val stop = FavoriteStop(
        id = PhoneSamples.STOP,
        stopId = PhoneSamples.STOP,
        name = PhoneSamples.STOP_NAME,
        customName = "Calimio-Sanin",
        anchorLatitude = 3.48,
        anchorLongitude = -76.49,
    )

    private fun count(text: String) =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun passedRows() = compose.onAllNodes(hasTestTag("arrival_passed"), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun thePassedTripStaysBesideItsNextOne() {
        val clock = MovingClock(PhoneSamples.time("14:48:09"))
        val fading = PhoneSamples.fedUpTo("14:50:09")
        val arrivals = PhoneSamples.answers.last { it.fetchedAt <= PhoneSamples.time("14:50:09") }.buses
        compose.setContent { AtTime(clock) { CaliGoTheme { FavoriteStopCard(stop, arrivals, fading = fading) } } }
        compose.waitForIdle()
        // Five live rows, three of them A47, and the A47 that passed
        assertEquals(1, passedRows())
        assertEquals(4, count("Valle Grande"))
        assertEquals(1, count("Passed 1 min ago"))
        // Still there 30 s before four minutes past the moment it reached the stop
        clock.advance(PhoneSamples.time("14:50:09") - clock.now)
        compose.waitForIdle()
        assertEquals(1, count("Passed 3 min ago"))
        // Then gone, the next trip still live
        clock.advance(31_000)
        compose.waitForIdle()
        assertEquals(0, passedRows())
        assertEquals(3, count("Valle Grande"))
    }
}
