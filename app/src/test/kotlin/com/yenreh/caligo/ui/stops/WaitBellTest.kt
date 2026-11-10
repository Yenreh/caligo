package com.yenreh.caligo.ui.stops

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.ui.common.AtTime
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.waits.NotificationAccess
import com.yenreh.caligo.waits.WaitControl
import com.yenreh.caligo.waits.WaitStart
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Waits as the screens start and stop them, recorded */
class FakeWaitControl : WaitControl {
    override val running = MutableStateFlow<Set<WaitKey>>(emptySet())
    val started = mutableListOf<Pair<AwaitedBus, FadingArrivals?>>()
    val stopped = mutableListOf<WaitKey>()
    var result = WaitStart.STARTED

    override fun start(bus: AwaitedBus, seed: FadingArrivals?): WaitStart {
        started.add(bus to seed)
        if (result == WaitStart.STARTED) running.value += bus.key
        return result
    }

    override fun stop(key: WaitKey) {
        stopped.add(key)
        running.value -= key
    }
}

/** The notification permission and settings as a test sets them, each request and opening recorded */
class FakeNotificationAccess(
    var granted: Boolean = false,
    /** What the prompt answers */
    var answer: Boolean = true,
    var enabled: Boolean = true,
) : NotificationAccess {
    var requests = 0
    var settingsOpened = 0

    override fun granted() = granted

    override suspend fun request(): Boolean {
        requests++
        granted = answer
        return answer
    }

    override fun enabled() = enabled && granted

    override fun openSettings() {
        settingsOpened++
    }
}

/** The bells of a favorite's rows, and the notification permission they ask for */
@RunWith(AndroidJUnit4::class)
class WaitBellTest {
    @get:Rule
    val compose = createComposeRule()

    private val control = FakeWaitControl()
    private val access = FakeNotificationAccess()

    private val stop = HomeSamples.stops[0]
    private val t31 = BusArrival("T31", "Universidades", HomeSamples.NOW + 4 * 60_000, "637001", stopName = "Plaza de Cayzedo A1")
    private val fading = FadingArrivals.EMPTY.fed("500800", listOf(t31), HomeSamples.NOW - 10_000)

    private fun show(content: @Composable () -> Unit = { Card(fading) }) {
        compose.setContent { AtTime(HomeSamples.NOW) { CaliGoTheme { WaitBellsHost(control, access, content) } } }
    }

    @Composable
    private fun Card(fading: FadingArrivals?) {
        FavoriteStopCard(stop = stop, arrivals = listOf(t31), fading = fading)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun tapBell() = compose.onNode(hasContentDescription("Notify me about the T31")).performClick()

    @Test
    fun theFirstTapAsksForThePermissionThenWaits() {
        show()
        tapBell()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists("wait_bell_on") }
        assertEquals(1, access.requests)
        val (bus, seed) = control.started.single()
        assertEquals(WaitKey("500800", "637001"), bus.key)
        assertEquals("Plaza de Cayzedo A1", bus.stopName)
        assertEquals(stop.anchorLatitude, bus.anchor.latitude, 0.0)
        assertTrue(!bus.area)
        assertEquals(fading, seed)
        // Filled, and named for what another tap does
        compose.onNode(hasContentDescription("Stop waiting for the T31")).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists("wait_bell") }
        assertEquals(listOf(bus.key), control.stopped)
    }

    @Test
    fun deniedStartsNoWaitAndSaysWhyWithTheWayToSettings() {
        access.answer = false
        show()
        tapBell()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists("wait_message") }
        compose.onNode(hasText("CaliGo follows the bus in a notification", substring = true)).assertExists()
        assertTrue(control.started.isEmpty())
        compose.onNode(hasText("Notification settings")).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists("wait_message") }
        assertEquals(1, access.settingsOpened)
        assertTrue(exists("wait_bell"))
    }

    @Test
    fun notificationsTurnedOffStartNoWaitEither() {
        access.granted = true
        access.enabled = false
        show()
        tapBell()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists("wait_message") }
        assertEquals(0, access.requests)
        assertTrue(control.started.isEmpty())
        compose.onNode(hasText("Close")).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { !exists("wait_message") }
        assertEquals(0, access.settingsOpened)
    }

    @Test
    fun aSixthWaitSaysTheCapIsReached() {
        access.granted = true
        control.result = WaitStart.CAP_REACHED
        show()
        tapBell()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists("wait_message") }
        compose.onNode(hasText("You are already waiting for 5 buses", substring = true)).assertExists()
        assertTrue(exists("wait_bell"))
    }

    @Test
    fun anAreasRowsKeptFromTheLastRunWaitForTheFirstAnswer() {
        val area = HomeSamples.stops[1]
        show { FavoriteStopCard(stop = area, arrivals = listOf(t31), fading = null) }
        compose.onNode(hasContentDescription("Notify me about the T31")).assertIsNotEnabled()
    }

    @Test
    fun aStopsRowsKeptFromTheLastRunKnowTheirStop() {
        access.granted = true
        show { Card(fading = null) }
        tapBell()
        compose.waitUntil(WAIT_TIMEOUT_MS) { exists("wait_bell_on") }
        assertEquals("500800", control.started.single().first.stopId)
    }

    @Test
    fun noBellsWithoutAHost() {
        compose.setContent { AtTime(HomeSamples.NOW) { CaliGoTheme { Card(fading) } } }
        assertTrue(!exists("wait_bell"))
    }
}
