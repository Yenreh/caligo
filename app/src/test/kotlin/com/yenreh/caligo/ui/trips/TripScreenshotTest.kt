package com.yenreh.caligo.ui.trips

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.captureNotification
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripNotice
import com.yenreh.caligo.trips.TripNotifications
import com.yenreh.caligo.trips.TripWords
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.waits.Alerts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The trip screen at Pixel size in Spanish, choosing where to get off and
 * on the ride, light, dark and at font scale 2, and the trip's
 * notification as the framework lays it out, under
 * build/outputs/roborazzi/trip_*.png
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class TripScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Three minutes in, by the rider's own fixes, getting off at Pampalinda */
    private val riding = Trip.start(TripFixtures.plan, NOW - 3 * MINUTE).fixed(fix(1210.0, NOW))

    private fun shot(name: String, content: TripContent, tag: String) {
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(content = content, title = "E21 a Universidades", subtitle = "Desde Plaza de Cayzedo A1")
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        compose.captureWindow(name)
    }

    private fun choosing(name: String) = shot(name, TripContent.Choosing(TripFixtures.plan), "trip_choose")

    private fun running(name: String) = shot(name, TripContent.Running(com.yenreh.caligo.domain.Journey.single(TripFixtures.plan, NOW - 3 * MINUTE).let { it.copy(ride = riding) }), "trip_running")

    @Test
    fun chooseLight() = choosing("trip_choose_light")

    @Test
    @Config(qualifiers = "+night")
    fun chooseDark() = choosing("trip_choose_dark")

    @Test
    @Config(fontScale = 2f)
    fun chooseFontScale2() = choosing("trip_choose_font2")

    @Test
    fun rideLight() = running("trip_ride_light")

    @Test
    @Config(qualifiers = "+night")
    fun rideDark() = running("trip_ride_dark")

    @Test
    @Config(fontScale = 2f)
    fun rideFontScale2() = running("trip_ride_font2")

    // With the map, over fixture tiles

    private fun map(name: String, journey: com.yenreh.caligo.domain.Journey, dark: Boolean) {
        val tiles = com.yenreh.caligo.ui.map.FixtureTiles(dark = dark)
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(
                    content = TripContent.Running(journey),
                    title = "Viaje a Unicentro",
                    tiles = TripMapTiles(androidx.compose.runtime.remember { tiles }, dark = dark, fadeIn = false),
                )
            }
        }
        // A close map covers a handful of tiles
        compose.waitUntil(WAIT_TIMEOUT_MS) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            tiles.asked.size >= 4
        }
        repeat(5) {
            Thread.sleep(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            compose.waitForIdle()
        }
        compose.captureWindow(name)
    }

    /** On the T31, five minutes into the planner trip, its bus beside the rider */
    private val onTheT31 = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 10 * MINUTE)
        .took("T31", NOW - 2 * MINUTE)
        .fixed(com.yenreh.caligo.trips.JourneyFixtures.fixAt(com.yenreh.caligo.trips.JourneyFixtures.calle5Route.pointAt(900.0), NOW))
        .withBuses("T31", listOf(com.yenreh.caligo.trips.JourneyFixtures.busOn(com.yenreh.caligo.trips.JourneyFixtures.calle5Route, "41022", 930.0)), NOW)

    private val walking = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 2 * MINUTE)

    @Test
    fun mapRideLight() = map("trip_map_ride_light", onTheT31, dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun mapRideDark() = map("trip_map_ride_dark", onTheT31, dark = true)

    @Test
    @Config(fontScale = 2f)
    fun mapRideFontScale2() = map("trip_map_ride_font2", onTheT31, dark = false)

    @Test
    fun mapWalkLight() = map("trip_map_walk_light", walking, dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun mapWalkDark() = map("trip_map_walk_dark", walking, dark = true)

    @Test
    @Config(fontScale = 2f)
    fun mapWalkFontScale2() = map("trip_map_walk_font2", walking, dark = false)

    // The notification, as the shade would inflate it off a device

    private fun notification(name: String, dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TripNotifications.createChannels(context)
        val words = TripWords(context.resources)
        val notice = TripNotice.of(riding, NOW, Alerts(), words, 15_000)
        val built = TripNotifications.build(context, riding.startedAt, notice, Alerts(), words)
        captureNotification(context, built, "trip_notification_$name", 1080, dark)
    }

    @Test
    fun notificationLight() = notification("light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun notificationDark() = notification("dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun notificationFontScale2() = notification("font2", dark = false)
}
