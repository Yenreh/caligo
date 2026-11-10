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
import com.yenreh.caligo.domain.RunningWait
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
    override val running = MutableStateFlow<List<RunningWait>>(emptyList())
    val started = mutableListOf<Pair<AwaitedBus, FadingArrivals?>>()
    val stopped = mutableListOf<WaitKey>()
    var result = WaitStart.STARTED

    override fun start(bus: AwaitedBus, seed: FadingArrivals?): WaitStart {
        started.add(bus to seed)
        if (result == WaitStart.STARTED) running.value += RunningWait(bus.key, bus.bus.arrivalTime, HomeSamples.NOW)
        return result
    }

    override fun stop(key: WaitKey) {
        stopped.add(key)
        running.value = running.value.filter { it.key != key }
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
        assertEquals(WaitKey("500800", "637001", t31.arrivalTime), bus.key)
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
    fun eachTripOfAVehicleHasItsOwnBell() {
        // At 514208 the A47 261001 reached the stop as its next trip showed; its wait runs
        access.granted = true
        val t = com.yenreh.caligo.domain.PhoneSamples::time
        val sampled = com.yenreh.caligo.domain.PhoneSamples.fedUpTo("14:46:39")
        val first = com.yenreh.caligo.domain.PhoneSamples.answers.first().buses.first { it.vehicleId == "261001" }
        control.running.value = listOf(RunningWait(WaitKey("514208", "261001", first.arrivalTime), t("14:46:39"), t("14:46:09")))
        val favorite = com.yenreh.caligo.domain.entities.FavoriteStop(
            id = "514208",
            stopId = "514208",
            name = "Cl 121 con Kr 26P",
            customName = "Calimio-Sanin",
            anchorLatitude = 3.48,
            anchorLongitude = -76.49,
            // The A47 alone, so the row limit leaves the next trip on the card
            lines = listOf("A47"),
        )
        val listed = com.yenreh.caligo.domain.PhoneSamples.answers.first { it.fetchedAt == t("14:46:39") }.buses
        compose.setContent {
            AtTime(t("14:46:50")) {
                CaliGoTheme { WaitBellsHost(control, access) { FavoriteStopCard(favorite, listed, fading = sampled) } }
            }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasContentDescription("Stop waiting for the A47")).fetchSemanticsNodes().size)
        val empty = compose.onAllNodes(hasContentDescription("Notify me about the A47"))
        assertEquals(3, empty.fetchSemanticsNodes().size)
        // The last of them, by time, is the next trip: a new wait
        empty[2].performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { control.started.isNotEmpty() }
        val (bus, _) = control.started.single()
        assertEquals("261001", bus.bus.vehicleId)
        assertEquals(t("14:46:39") + 2_808_000, bus.bus.arrivalTime)
        assertTrue(control.stopped.isEmpty())
    }

    @Test
    fun noBellsWithoutAHost() {
        compose.setContent { AtTime(HomeSamples.NOW) { CaliGoTheme { Card(fading) } } }
        assertTrue(!exists("wait_bell"))
    }
}
