package com.yenreh.caligo.ui.trips

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.navigation.PlaceKey
import com.yenreh.caligo.ui.navigation.Route
import com.yenreh.caligo.ui.navigation.key
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The planner's parts below its screens: the open option's map, the
 * places handed back and saved, the keys the back stack saves, and how
 * times, minutes and metres are written
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanTripPartsTest {
    private lateinit var trips: TripFixtures

    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        trips = TripFixtures()
    }

    @After
    fun clear() {
        trips.clear()
        Dispatchers.resetMain()
    }

    private val viewport = DpSize(400.dp, 250.dp)

    // The open option's map

    @Test
    fun theOpenOptionsMapIsKeptWhileItStaysOpenAndMadeAnewEachOpening() {
        val model = trips.planner()
        assertNull(model.mapFor(viewport))

        model.toggle(0)
        val map = model.mapFor(viewport)!!
        // Kept through anything, the screen turning included
        assertSame(map, model.mapFor(DpSize(250.dp, 400.dp)))
        model.chooseLine(0, 0, "T1")
        assertSame(map, model.mapFor(viewport))

        model.toggle(0)
        assertNull(model.mapFor(viewport))
        model.toggle(0)
        assertNotSame(map, model.mapFor(viewport))
    }

    @Test
    fun theMapFramesTheWholeTripNorthUp() {
        val model = trips.planner()
        model.toggle(0)
        val map = model.mapFor(viewport)!!

        assertTrue(map.camera.zoom in 11.0..16.0)
        assertEquals(0f, map.camera.rotation)
        assertTrue(!map.rotatable)
        assertEquals(11.0, map.minZoom, 0.0)
        assertEquals(18.0, map.maxZoom, 0.0)
        // Midway between the two places, which bound the trip
        assertEquals((trips.from.longitude + trips.to.longitude) / 2, map.camera.center.longitude, 1e-9)
    }

    @Test
    fun aShortTripIsFramedNoCloserThanZoomSixteen() {
        val model = trips.planner(destination = trips.place(1.0, 0.5, "Near"))
        model.toggle(0)

        assertEquals(16.0, model.mapFor(viewport)!!.camera.zoom, 0.0)
    }

    @Test
    fun tripPointsAreBothPlacesAndEveryStopRidden() {
        val model = trips.planner()
        val option = model.screen.value.timed.getValue(0)

        val points = tripPoints(trips.from, trips.to, option)

        assertEquals(2 + 13, points.size)
        assertEquals(GeoPoint(trips.to.latitude, trips.to.longitude), points[1])
        assertEquals(13, tripPoints(null, null, option).size)
    }

    // What the pickers hand back

    @Test
    fun aPlaceAndAPointAreHandedOverOnceAndSavedInPlainValues() {
        val device = TripPlace(3.4, -76.5, "My location", isDevice = true)
        val picked = PickedPlaces(place = PickedPlace(origin = true, place = device), point = GeoPoint(3.41, -76.51))

        val plain = SaverScope { it is Boolean || it is Double || it is String || it is List<*> }
        val saved = with(PickedPlaces.Saver) { plain.save(picked) }!!
        val restored = PickedPlaces.Saver.restore(saved)!!

        assertEquals(PickedPlace(origin = true, place = device), restored.takePlace())
        assertNull(restored.takePlace())
        assertEquals(GeoPoint(3.41, -76.51), restored.takePoint())
        assertNull(restored.point)
        // Nothing waiting saves as nothing
        val empty = PickedPlaces.Saver.restore(with(PickedPlaces.Saver) { plain.save(PickedPlaces()) }!!)!!
        assertNull(empty.place)
        assertNull(empty.point)
    }

    @Test
    fun whatNobodyWaitsForIsLetGo() {
        val picked = PickedPlaces(place = PickedPlace(false, trips.to), point = GeoPoint(3.0, -76.0))

        picked.forget(planner = true, picker = true)
        assertEquals(trips.to, picked.place!!.place)

        picked.forget(planner = true, picker = false)
        assertNull(picked.point)
        assertEquals(trips.to, picked.place!!.place)

        picked.forget(planner = false, picker = false)
        assertNull(picked.place)
    }

    @Test
    fun thePlannersKeysSaveWithTheBackStack() {
        val keys = listOf(
            Route.PlanTrip(),
            Route.PlanTrip(TripPlace(3.45, -76.53, "Estación Universidades").key),
            Route.PlacePicker(origin = true, near = TripPlace(3.4, -76.5, "My location", isDevice = true).key),
            Route.PlacePicker(origin = false),
            Route.MapPoint(PlaceKey(3.4, -76.5, "Casa")),
        )
        for (key in keys) {
            val json = Json.encodeToString(Route.serializer(), key)
            assertEquals(key, Json.decodeFromString(Route.serializer(), json))
        }
        assertEquals(TripPlace(3.4, -76.5, "My location", isDevice = true), (keys[2] as Route.PlacePicker).near!!.place)
    }

    // How it is written

    @Test
    fun timesAreWrittenAsFlutterWritesThem() {
        val morning = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 29, 7, 5)
        }.timeInMillis
        val evening = morning + 12 * 3600_000L

        // Spanish keeps H:mm either way, as MaterialLocalizationEs
        assertEquals("7:05", clockText(morning, hours24 = true, spanish = true))
        assertEquals("07:05", clockText(morning, hours24 = true, spanish = false))
        assertEquals("19:05", clockText(evening, hours24 = true, spanish = false))
        assertEquals("7:05", clockText(morning, hours24 = false, spanish = true))
        assertEquals("19:05", clockText(evening, hours24 = false, spanish = true))
        assertEquals("7:05 AM", clockText(morning, hours24 = false, spanish = false))
        assertEquals("7:05 PM", clockText(evening, hours24 = false, spanish = false))
    }

    @Test
    fun minutesAreWholeAndNeverZeroAndMetresGoByTens() {
        assertEquals(1, minutes(0.seconds))
        assertEquals(1, minutes(89.seconds))
        assertEquals(2, minutes(90.seconds))
        assertEquals(12, minutes(719.seconds))
        assertEquals(0, roundTo10(4.9))
        assertEquals(10, roundTo10(5.0))
        assertEquals(290, roundTo10(286.0))
    }
}
