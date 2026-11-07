package com.yenreh.caligo.data.datasources

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoriteStop
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** card_local_datasource.dart and stops_local_datasource.dart, on SQLite */
@RunWith(AndroidJUnit4::class)
class LocalDatasourcesTest {
    private val database = AppDatabase(ApplicationProvider.getApplicationContext(), name = null)
    private val cards = CardLocalDatasource(database)
    private val stops = StopsLocalDatasource(database)

    @After
    fun close() = database.close()

    private fun card(internalId: String, id: String, name: String, position: Int = 0) =
        CardEntity(internalId = internalId, id = id, name = name, position = position)

    private fun stop(id: String, name: String, position: Int = 0) =
        FavoriteStop(id = id, name = name, anchorLatitude = 3.45, anchorLongitude = -76.53, position = position)

    @Test
    fun cardsComeInTheirOrderThenByName() = runTest {
        cards.insertCard(card("i1", "1", "B", position = 1))
        cards.insertCard(card("i2", "2", "Z", position = 0))
        cards.insertCard(card("i3", "3", "A", position = 1))

        assertEquals(listOf("Z", "A", "B"), cards.getAllCards().map { it.name })
    }

    @Test
    fun aCardKeepsEveryField() = runTest {
        val saved = CardEntity(
            internalId = "i1",
            id = "06051868",
            prefix = "19",
            suffix = "981",
            name = "Mine",
            position = 2,
            balance = 15300.0,
            lastUpdate = 1785631011000,
        )
        cards.insertCard(saved)

        assertEquals(saved, cards.getCardById("06051868"))
        assertNull(cards.getCardById("nope"))
    }

    @Test
    fun insertingTheSameInternalIdReplacesTheCard() = runTest {
        cards.insertCard(card("i1", "1", "Old"))
        cards.insertCard(card("i1", "1", "New"))

        assertEquals(listOf("New"), cards.getAllCards().map { it.name })
    }

    @Test
    fun aBalanceUpdateTouchesOnlyTheBalance() = runTest {
        cards.insertCard(card("i1", "1", "Mine"))

        cards.updateCardBalance("1", 9000.0, 1790608530123)
        cards.updateCard(cards.getCardById("1")!!.copy(name = "Renamed"))

        val updated = cards.getCardById("1")!!
        assertEquals(9000.0, updated.balance!!, 0.0)
        assertEquals(1790608530123, updated.lastUpdate)
        assertEquals("Renamed", updated.name)

        cards.deleteCard("1")
        assertTrue(cards.getAllCards().isEmpty())
    }

    @Test
    fun aFavoriteKeepsEveryField() = runTest {
        val saved = FavoriteStop(
            id = "500800",
            name = "Plaza de Cayzedo A1",
            customName = "Trabajo",
            anchorLatitude = 3.4516,
            anchorLongitude = -76.532,
            position = 1,
            stopId = "500800",
            missingCount = 2,
            showOnHome = false,
            lines = listOf("E21", "P27D"),
        )
        stops.insert(saved)
        stops.insert(stop("station-1", "Alamos").copy(stopId = null))

        val read = stops.getFavorites()
        assertEquals(listOf(stop("station-1", "Alamos"), saved), read)
        assertTrue(read.first().isArea)
    }

    @Test
    fun savingAStopTwiceFails() = runTest {
        stops.insert(stop("1", "A"))

        assertThrowsSuspend<SQLiteConstraintException> { stops.insert(stop("1", "B")) }
    }

    @Test
    fun favoritesUpdateAndCountPositions() = runTest {
        assertEquals(0, stops.nextPosition())
        stops.insert(stop("1", "A", position = 4))
        assertEquals(5, stops.nextPosition())

        stops.update(stop("1", "Renamed", position = 4).copy(lines = emptyList(), missingCount = 1))
        assertEquals("Renamed", stops.getFavorites().single().name)
        assertEquals(1, stops.getFavorites().single().missingCount)
        assertTrue(stops.exists("1"))

        stops.delete("1")
        assertFalse(stops.exists("1"))
    }
}
