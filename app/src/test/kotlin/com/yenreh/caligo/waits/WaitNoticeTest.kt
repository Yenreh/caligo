package com.yenreh.caligo.waits

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.NBSP
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.waits.WaitFixtures.MINUTE
import com.yenreh.caligo.waits.WaitFixtures.NOW
import com.yenreh.caligo.waits.WaitFixtures.answer
import com.yenreh.caligo.waits.WaitFixtures.awaited
import com.yenreh.caligo.waits.WaitFixtures.e21
import com.yenreh.caligo.waits.WaitFixtures.t31
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A wait's notification as a pure function of the wait, the time and the alert settings */
@RunWith(AndroidJUnit4::class)
class WaitNoticeTest {
    private val words = WaitWords(ApplicationProvider.getApplicationContext<Context>().resources)
    private val start = Wait.start(awaited(t31), NOW)

    private fun notice(wait: Wait, now: Long, alerts: Alerts = Alerts()) =
        WaitNotice.of(wait, now, alerts, words, nextPostIn = 15_000)

    @Test
    fun countsDownToTheBus() {
        val notice = notice(start, NOW + 6 * MINUTE)
        assertEquals("T31 to Universidades, 4${NBSP}min", notice.title)
        assertEquals("Plaza de Cayzedo A1", notice.text)
        assertEquals("T31 to Universidades, 4${NBSP}min, Plaza de Cayzedo A1", notice.summary)
        assertEquals("4${NBSP}min", notice.chip)
        assertEquals(t31.arrivalTime, notice.countdownTo)
        assertEquals(listOf(WaitAction.TOOK, WaitAction.STOP), notice.actions)
        assertEquals(WaitChannel.WAITS, notice.channel)
        assertFalse(notice.alerting)
        // Six of ten minutes gone, counted as the title counts them
        assertEquals(10, notice.progressMax)
        assertEquals(6, notice.progress)
        assertEquals(WaitNotice.timeoutAfter(15_000), notice.timeoutAfter)
    }

    @Test
    @Config(qualifiers = "es")
    fun inSpanish() {
        val spanish = WaitWords(ApplicationProvider.getApplicationContext<Context>().resources)
        val notice = WaitNotice.of(start, NOW + 6 * MINUTE, Alerts(), spanish, 15_000)
        assertEquals("T31 a Universidades, 4${NBSP}min, Plaza de Cayzedo A1", notice.summary)
        assertEquals("Lo tomé", spanish.action(WaitAction.TOOK))
        assertEquals("Dejar de esperar", spanish.action(WaitAction.STOP))
        assertEquals("No", spanish.action(WaitAction.NOT_TAKEN))
    }

    @Test
    fun arrivingNow() {
        val notice = notice(start, t31.arrivalTime - 10_000)
        assertEquals("T31 to Universidades, arriving now", notice.title)
        assertEquals("Now", notice.chip)
    }

    @Test
    fun aBusNotSeenJustNowKeepsItsLastTime() {
        val out = start.fed(answer(NOW + 30_000, e21))
        val notice = notice(out, NOW + MINUTE)
        assertEquals("T31 to Universidades, 9${NBSP}min", notice.title)
        assertEquals("Plaza de Cayzedo A1 · not seen just now", notice.text)
    }

    @Test
    fun atTheStopAskingAndPassed() {
        val due = e21.arrivalTime
        val wait = Wait.start(awaited(e21), NOW).fed(answer(due - MINUTE, e21)).fed(answer(due - 30_000))
        val at = notice(wait, due)
        assertEquals("E21 to Terminal Paso del Comercio, at the stop", at.title)
        assertEquals("Here", at.chip)
        assertNull(at.countdownTo)
        assertEquals(at.progressMax, at.progress)
        val asking = notice(wait, due + MINUTE)
        assertEquals("Did you take the E21?", asking.title)
        assertEquals("E21 to Terminal Paso del Comercio, Plaza de Cayzedo A1", asking.text)
        assertNull(asking.chip)
        // Answered: "I took it" or "No"
        assertEquals(listOf(WaitAction.TOOK, WaitAction.NOT_TAKEN), asking.actions)
        assertEquals("No", words.action(WaitAction.NOT_TAKEN))
        assertEquals(listOf(WaitAction.TOOK, WaitAction.STOP), at.actions)
    }

    @Test
    fun failuresShowNoDataInsteadOfACountdown() {
        val failing = start.failed(NOW + MINUTE)
        val notice = notice(failing, NOW + MINUTE)
        assertEquals("T31 to Universidades, no data", notice.title)
        assertNull(notice.chip)
        assertNull(notice.countdownTo)
        val ended = notice(failing.copy(telling = com.yenreh.caligo.domain.WaitEnding.NO_DATA), NOW + 6 * MINUTE)
        assertEquals("No data for a while", ended.title)
    }

    @Test
    fun noLongerSeen() {
        val telling = start.copy(telling = com.yenreh.caligo.domain.WaitEnding.NOT_SEEN, tellingSince = NOW + 5 * MINUTE)
        val notice = notice(telling, NOW + 5 * MINUTE)
        assertEquals("The bus is no longer seen", notice.title)
        assertEquals("T31 to Universidades, Plaza de Cayzedo A1", notice.text)
    }

    @Test
    fun theAlertGoesToTheChannelTheSettingsAsk() {
        val near = t31.arrivalTime - 2 * MINUTE
        assertEquals(WaitChannel.ALERT_SILENT, notice(start, near).channel)
        assertTrue(notice(start, near).alerting)
        assertEquals(WaitChannel.ALERT_VIBRATE, notice(start, near, Alerts(vibrate = true)).channel)
        assertEquals(WaitChannel.ALERT_SOUND, notice(start, near, Alerts(sound = true)).channel)
        assertEquals(WaitChannel.ALERT_SOUND_VIBRATE, notice(start, near, Alerts(sound = true, vibrate = true)).channel)
        // Once alerted it stays there, silent from then on
        val after = notice(start.copy(alerted = true), near + 15_000, Alerts(sound = true))
        assertEquals(WaitChannel.ALERT_SOUND, after.channel)
        assertFalse(after.alerting)
        // Its channel turned off: no alert, and never a post there
        val off = WaitNotice.of(start, near, Alerts(sound = true), words, 15_000, alertOn = false)
        assertFalse(off.alerting)
        assertEquals(WaitChannel.WAITS, off.channel)
        assertEquals(WaitChannel.WAITS, WaitNotice.of(start.copy(alerted = true), near, Alerts(), words, 15_000, alertOn = false).channel)
        // Before it, the silent channel of updates
        assertEquals(WaitChannel.WAITS, notice(start, near - 1, Alerts(sound = true)).channel)
    }
}
