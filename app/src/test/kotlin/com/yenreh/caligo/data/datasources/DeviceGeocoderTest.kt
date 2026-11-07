package com.yenreh.caligo.data.datasources

import android.location.Address
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.domain.CALI_BOUNDS
import com.yenreh.caligo.domain.GeoBounds
import com.yenreh.caligo.domain.GeocodedPlace
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowGeocoder

/**
 * device_geocoder.dart with MainActivity.kt's bridge: Android's geocoder
 * asked for five places within Cali, and nothing found when there is no
 * geocoder or it does not answer
 */
@RunWith(AndroidJUnit4::class)
class DeviceGeocoderTest {
    /** A platform geocoder set by hand, recording what it is asked */
    private class FakePlatform(
        var present: Boolean = true,
        var answer: List<GeocodedPlace> = emptyList(),
        var failure: Exception? = null,
    ) : PlatformGeocoder {
        val asked = mutableListOf<Triple<String, Int, GeoBounds>>()

        override fun isPresent() = present

        override suspend fun search(query: String, maxResults: Int, bounds: GeoBounds): List<GeocodedPlace> {
            asked.add(Triple(query, maxResults, bounds))
            failure?.let { throw it }
            return answer
        }
    }

    @After
    fun reset() = ShadowGeocoder.reset()

    @Test
    fun asksForFivePlacesWithinCali() = runTest {
        val place = GeocodedPlace("Cl. 4 #75-71, Cali, Valle del Cauca, Colombia", 3.3899242, -76.5461137)
        val platform = FakePlatform(answer = listOf(place))

        val found = DeviceGeocoder(platform).search("Cl. 4 #75-71, Cali")

        assertEquals(listOf(place), found)
        assertEquals(listOf(Triple("Cl. 4 #75-71, Cali", 5, CALI_BOUNDS)), platform.asked)
        assertEquals(GeoBounds(south = 3.30, west = -76.62, north = 3.52, east = -76.44), CALI_BOUNDS)
    }

    @Test
    fun withoutAGeocoderFindsNothingAndAsksNothing() = runTest {
        val platform = FakePlatform(present = false)

        assertTrue(DeviceGeocoder(platform).search("Unicentro, Cali").isEmpty())
        assertTrue(platform.asked.isEmpty())
    }

    @Test
    fun aServiceThatDoesNotAnswerFindsNothing() = runTest {
        val platform = FakePlatform(failure = IOException("grpc failed"))

        assertTrue(DeviceGeocoder(platform).search("Unicentro, Cali").isEmpty())
        assertEquals(1, platform.asked.size)
    }

    @Test
    fun aSearchLetGoIsNotTakenForNothingFound() = runTest {
        val platform = FakePlatform(failure = CancellationException("left"))

        assertThrowsSuspend<CancellationException> { DeviceGeocoder(platform).search("Unicentro, Cali") }
    }

    @Test
    fun anAddressIsHandedOverByItsFirstLineOrNone() {
        val written = Address(Locale.forLanguageTag("es-CO")).apply {
            setAddressLine(0, "Cra. 100 #5 - 169, Las Vegas, Cali, Valle del Cauca, Colombia")
            latitude = 3.3740819
            longitude = -76.5395399
        }
        val bare = Address(Locale.forLanguageTag("es-CO")).apply {
            latitude = 3.44
            longitude = -76.60
        }

        assertEquals(
            GeocodedPlace("Cra. 100 #5 - 169, Las Vegas, Cali, Valle del Cauca, Colombia", 3.3740819, -76.5395399),
            AndroidGeocoder.placeOf(written),
        )
        assertEquals(GeocodedPlace("", 3.44, -76.60), AndroidGeocoder.placeOf(bare))
    }

    @Test
    fun tellsWhetherTheDeviceHasAGeocoder() = runTest {
        val android = AndroidGeocoder(ApplicationProvider.getApplicationContext())
        ShadowGeocoder.setIsPresent(false)

        assertFalse(android.isPresent())
        assertTrue(DeviceGeocoder(android).search("Unicentro, Cali").isEmpty())
    }
}
