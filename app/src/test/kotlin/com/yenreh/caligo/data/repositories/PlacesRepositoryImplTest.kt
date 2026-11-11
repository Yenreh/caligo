package com.yenreh.caligo.data.repositories

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.PlacesLocalDatasource
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.entities.matching
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The saved places on a real database in memory: saved, listed in order, renamed, deleted and kept */
@RunWith(AndroidJUnit4::class)
class PlacesRepositoryImplTest {
    private val database = AppDatabase(ApplicationProvider.getApplicationContext(), name = null)

    @After
    fun close() = database.close()

    private fun repository() = PlacesRepositoryImpl(PlacesLocalDatasource(database))

    private val home = TripPlace(3.4372, -76.5225, "Cl 5 # 38-20", kind = PlaceKind.ADDRESS)
    private val university = TripPlace(3.3705, -76.531, "Universidades", kind = PlaceKind.STOP, stopId = "t12")
    private val mall = TripPlace(3.3739, -76.5395, "Unicentro", kind = PlaceKind.PLACE, detail = "Cra. 100 #5-169")

    @Test
    fun savesInOrderWithWhatPlansThereAgain() = runTest {
        val places = repository()
        assertTrue(places.places.value.isEmpty())

        val casa = places.add("  Casa ", home)
        places.add("U", university)
        places.add("Centro comercial", mall)

        assertEquals(
            FavoritePlace(casa.id, "Casa", PlaceKind.ADDRESS, "Cl 5 # 38-20", null, 3.4372, -76.5225, null, 0),
            casa,
        )
        assertEquals(listOf("Casa", "U", "Centro comercial"), places.places.value.map { it.name })
        assertEquals(listOf(0, 1, 2), places.places.value.map { it.position })
        val saved = places.places.value[2]
        assertEquals(mall.copy(name = "Centro comercial", savedId = saved.id, label = "Unicentro"), saved.tripPlace)
        // Saved from a field showing a saved place, it keeps what that was called
        assertEquals("Unicentro", places.add("Mall", saved.tripPlace).label)
        places.delete(places.places.value.last().id)
        assertEquals("t12", places.places.value[1].stopId)

        // Kept: another repository reads them back the same
        val again = repository()
        again.load()
        assertEquals(places.places.value, again.places.value)
    }

    @Test
    fun renamesAndDeletes() = runTest {
        val places = repository()
        val casa = places.add("Casa", home)
        val u = places.add("U", university)

        places.rename(casa.id, "Mi   casa")
        assertEquals(listOf("Mi casa", "U"), places.places.value.map { it.name })

        places.delete(u.id)
        assertEquals(listOf("Mi casa"), places.places.value.map { it.name })

        // A place added after a delete still goes last
        places.add("U again", university)
        assertEquals(listOf("Mi casa", "U again"), places.places.value.map { it.name })
        assertEquals(listOf(0, 1), places.places.value.map { it.position })
    }

    @Test
    fun aNameMustHoldSomething() = runTest {
        val places = repository()
        assertThrowsSuspend<IllegalArgumentException> { places.add("   ", home) }
        val casa = places.add("Casa", home)
        assertThrowsSuspend<IllegalArgumentException> { places.rename(casa.id, "") }
        assertEquals(listOf("Casa"), places.places.value.map { it.name })
    }

    @Test
    fun aSavedPlaceIsFoundByItsStopOrWhereItIs() = runTest {
        val places = repository()
        places.add("Casa", home)
        places.add("U", university)
        val saved = places.places.value

        // About 33 m off: the same place, as a geocoder moves it
        assertEquals("Casa", saved.matching(home.copy(latitude = home.latitude + 0.0003, name = "Calle 5"))?.name)
        // About 55 m off: another
        assertNull(saved.matching(home.copy(latitude = home.latitude + 0.0005)))
        // A stop by its id, wherever it is said to be
        assertEquals("U", saved.matching(university.copy(latitude = 3.38))?.name)
        // Another stop at the same spot is not it
        assertNull(saved.matching(university.copy(stopId = "t13")))
        // A plain place where the stop is
        assertEquals("U", saved.matching(TripPlace(university.latitude, university.longitude, "Near Universidades"))?.name)
        // Never the device
        assertNull(saved.matching(home.copy(isDevice = true)))
    }
}
