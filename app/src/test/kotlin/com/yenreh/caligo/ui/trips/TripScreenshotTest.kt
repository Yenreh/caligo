package com.yenreh.caligo.ui.trips

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.captureNotification
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.settlePress
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.trips.TripFixtures
import com.yenreh.caligo.trips.TripFixtures.MINUTE
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.fix
import com.yenreh.caligo.trips.TripNotice
import com.yenreh.caligo.trips.TripNotifications
import com.yenreh.caligo.trips.TripWords
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.map.waitForMapTiles
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

    /** The ride just finished: the screen tells it so, with Undo while it lasts */
    private fun finished(name: String) {
        val journey = com.yenreh.caligo.domain.Journey.single(TripFixtures.plan, NOW - 3 * MINUTE).copy(ride = riding)
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(
                    content = TripContent.Ended(com.yenreh.caligo.trips.EndedTrip(journey, com.yenreh.caligo.domain.TripEnding.FINISHED)),
                    title = "E21 a Universidades",
                    finishing = com.yenreh.caligo.trips.FinishingTrip(journey, NOW + 8_000),
                )
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasText("Deshacer")).fetchSemanticsNodes().isNotEmpty() }
        compose.captureWindow(name)
    }

    @Test
    fun finishedLight() = finished("trip_finished_light")

    @Test
    @Config(qualifiers = "+night")
    fun finishedDark() = finished("trip_finished_dark")

    @Test
    @Config(fontScale = 2f)
    fun finishedFontScale2() = finished("trip_finished_font2")

    /** On the ride with the approximate permission alone: no fix to go by, and why */
    private fun approximate(name: String) = shot(
        name,
        TripContent.Running(com.yenreh.caligo.domain.Journey.single(TripFixtures.plan, NOW - 3 * MINUTE).copy(approximate = true)),
        "trip_running",
    )

    @Test
    fun rideApproximateLight() = approximate("trip_ride_approximate_light")

    @Test
    @Config(qualifiers = "+night")
    fun rideApproximateDark() = approximate("trip_ride_approximate_dark")

    @Test
    @Config(fontScale = 2f)
    fun rideApproximateFontScale2() = approximate("trip_ride_approximate_font2")

    /** On the ride, a stop further on tapped: where the rider gets off now, with Undo */
    private fun changed(name: String) {
        // As the trip takes the change: the stop tapped is the one to get off at now
        var journey by androidx.compose.runtime.mutableStateOf(
            com.yenreh.caligo.domain.Journey.single(TripFixtures.plan, NOW - 3 * MINUTE).copy(ride = riding),
        )
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(
                    content = TripContent.Running(journey),
                    title = "E21 a Universidades",
                    subtitle = "Desde Plaza de Cayzedo A1",
                    onAlight = { alight -> journey = journey.copy(ride = journey.ride?.alightAt(alight, NOW)) },
                )
            }
        }
        val stop = "trip_stop_${TripFixtures.out[9].stopId}"
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("trip_running")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag(stop))
        compose.onNodeWithTag(stop).performClick()
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasText("Ahora te bajas en Universidades")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("trip_alight") and androidx.compose.ui.test.hasAnyDescendant(hasText("Universidades"))).assertExists()
        compose.settlePress()
        compose.captureWindow(name)
    }

    @Test
    fun rideChangedLight() = changed("trip_ride_changed_light")

    @Test
    @Config(qualifiers = "+night")
    fun rideChangedDark() = changed("trip_ride_changed_dark")

    @Test
    @Config(fontScale = 2f)
    fun rideChangedFontScale2() = changed("trip_ride_changed_font2")

    // With the map, over fixture tiles

    private fun map(name: String, journey: com.yenreh.caligo.domain.Journey, dark: Boolean, heard: com.yenreh.caligo.trips.RideBuses? = null) {
        val tiles = com.yenreh.caligo.ui.map.FixtureTiles(dark = dark)
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(
                    content = TripContent.Running(journey),
                    title = "Viaje a Unicentro",
                    tiles = TripMapTiles(androidx.compose.runtime.remember { tiles }, dark = dark, fadeIn = false),
                    rideBuses = heard,
                )
            }
        }
        // A close map covers a handful of tiles
        compose.waitForMapTiles { tiles.asked.size >= 4 }
        compose.captureWindow(name)
    }

    /** On the T31, five minutes into the planner trip, its bus beside the rider */
    private val onTheT31 = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 10 * MINUTE)
        .took("T31", NOW - 2 * MINUTE)
        .fixed(com.yenreh.caligo.trips.JourneyFixtures.fixAt(com.yenreh.caligo.trips.JourneyFixtures.calle5Route.pointAt(900.0), NOW))
        .withBuses("T31", listOf(com.yenreh.caligo.trips.JourneyFixtures.busOn(com.yenreh.caligo.trips.JourneyFixtures.calle5Route, "41022", 930.0)), NOW)

    private val walking = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 2 * MINUTE)

    /** On foot 100 m from the stop, the T31 due in 2 min and the E21 in 5: a button for each line */
    private val nearTheStop = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 2 * MINUTE)
        .fixed(
            com.yenreh.caligo.trips.JourneyFixtures.fixAt(
                com.yenreh.caligo.trips.JourneyFixtures.east(com.yenreh.caligo.domain.GeoPoint(TripFixtures.out[0].latitude, TripFixtures.out[0].longitude), -100.0),
                NOW,
            ),
        )

    private val nearAwaited = listOf(
        com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 2 * MINUTE),
        com.yenreh.caligo.trips.AwaitedLine("E21", NOW + 5 * MINUTE),
    )

    private fun nearStop(name: String) {
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(content = TripContent.Running(nearTheStop), title = "Viaje a Unicentro", awaited = nearAwaited)
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("trip_took_T31")).fetchSemanticsNodes().isNotEmpty() }
        compose.captureWindow(name)
    }

    @Test
    fun walkNearLight() = nearStop("trip_walk_near_light")

    @Test
    @Config(qualifiers = "+night")
    fun walkNearDark() = nearStop("trip_walk_near_dark")

    @Test
    @Config(fontScale = 2f)
    fun walkNearFontScale2() = nearStop("trip_walk_near_font2")

    // The ride's lines at its stop: each with its next buses, and one chosen

    private val toTheStop = com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - MINUTE)

    /** About 4 minutes on foot from the stop: the T31 in 2 cannot be made */
    private fun heardAtTheStop(journey: com.yenreh.caligo.domain.Journey) = com.yenreh.caligo.trips.JourneyFixtures.heard(
        journey,
        NOW,
        com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t1", NOW, 2.0),
        com.yenreh.caligo.trips.JourneyFixtures.bus("E21", "e1", NOW, 8.0),
        com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t2", NOW, 12.0),
        com.yenreh.caligo.trips.JourneyFixtures.bus("E21", "e2", NOW, 20.0),
        com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t3", NOW, 25.0),
        com.yenreh.caligo.trips.JourneyFixtures.bus("T31", "t4", NOW, 40.0),
    )

    private fun lines(name: String, journey: com.yenreh.caligo.domain.Journey) {
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(content = TripContent.Running(journey), title = "Viaje a Unicentro", rideBuses = heardAtTheStop(journey))
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("trip_lines")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("trip_running").performScrollToNode(hasTestTag("trip_lines"))
        compose.captureWindow(name)
    }

    @Test
    fun linesLight() = lines("trip_lines_any_light", toTheStop)

    @Test
    @Config(qualifiers = "+night")
    fun linesDark() = lines("trip_lines_any_dark", toTheStop)

    @Test
    @Config(qualifiers = "+en")
    fun linesEnglish() = lines("trip_lines_any_en", toTheStop)

    @Test
    @Config(fontScale = 2f)
    fun linesFontScale2() = lines("trip_lines_any_font2", toTheStop)

    /** At the stop, the T31 chosen */
    private val waitingT31 = toTheStop.fixed(
        com.yenreh.caligo.trips.JourneyFixtures.fixAt(com.yenreh.caligo.domain.GeoPoint(TripFixtures.out[0].latitude, TripFixtures.out[0].longitude), NOW),
    ).choosing("T31")

    @Test
    fun linesChosenLight() = lines("trip_lines_chosen_light", waitingT31)

    @Test
    @Config(qualifiers = "+en")
    fun linesChosenEnglish() = lines("trip_lines_chosen_en", waitingT31)

    @Test
    @Config(fontScale = 2f)
    fun linesChosenFontScale2() = lines("trip_lines_chosen_font2", waitingT31)

    /** On foot to the A47, a ride of one line: its times, no choice */
    @Test
    fun linesOfOneLine() = lines(
        "trip_lines_one",
        com.yenreh.caligo.domain.Journey.planned(com.yenreh.caligo.trips.JourneyFixtures.option, "Unicentro", NOW - 10 * MINUTE)
            .took("T31", NOW - 8 * MINUTE).copy(index = 2, ride = null, legStartedAt = NOW - MINUTE),
    )

    /** At the stop as the E21 left: did the rider take it? */
    private fun asking(name: String) {
        val journey = toTheStop.fixed(
            com.yenreh.caligo.trips.JourneyFixtures.fixAt(com.yenreh.caligo.domain.GeoPoint(TripFixtures.out[0].latitude, TripFixtures.out[0].longitude), NOW),
        )
        compose.setContentAt(NOW) {
            CaliGoTheme {
                TripScreen(
                    content = TripContent.Running(journey),
                    title = "Viaje a Unicentro",
                    awaited = listOf(
                        com.yenreh.caligo.trips.AwaitedLine("E21", NOW - MINUTE, asking = true),
                        com.yenreh.caligo.trips.AwaitedLine("T31", NOW + 4 * MINUTE),
                    ),
                )
            }
        }
        compose.waitUntil(WAIT_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("trip_asking")).fetchSemanticsNodes().isNotEmpty() }
        compose.captureWindow(name)
    }

    @Test
    fun askingLight() = asking("trip_asking_light")

    @Test
    @Config(qualifiers = "+night")
    fun askingDark() = asking("trip_asking_dark")

    @Test
    @Config(qualifiers = "+en")
    fun askingEnglish() = asking("trip_asking_en")

    @Test
    @Config(fontScale = 2f)
    fun askingFontScale2() = asking("trip_asking_font2")

    @Test
    fun mapChosenLight() = map("trip_map_chosen_light", toTheStop.choosing("T31"), dark = false, heard = heardAtTheStop(toTheStop))

    @Test
    @Config(qualifiers = "+night")
    fun mapChosenDark() = map("trip_map_chosen_dark", toTheStop.choosing("T31"), dark = true, heard = heardAtTheStop(toTheStop))

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
