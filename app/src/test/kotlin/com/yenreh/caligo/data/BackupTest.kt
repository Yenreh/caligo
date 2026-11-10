@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.CardRemoteDatasource
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.datasources.StopsRemoteDatasource
import com.yenreh.caligo.data.json.JsonFormatException
import com.yenreh.caligo.data.json.JsonTypeException
import com.yenreh.caligo.data.json.dartTryParse
import com.yenreh.caligo.data.repositories.CardRepositoryImpl
import com.yenreh.caligo.data.repositories.StopsRepositoryImpl
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.HomeContent
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * exportData and importData of cards_provider.dart, importFavorites of
 * stops_provider.dart and importSettings of settings_provider.dart.
 * cards_provider_test.dart has no import or export cases to port.
 */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val opened = mutableListOf<AutoCloseable>()

    @After
    fun close() = opened.forEach { it.close() }

    /** A device of its own: database, settings and the backup over them */
    private inner class Device(scope: TestScope, name: String) {
        val database = AppDatabase(ApplicationProvider.getApplicationContext(), name = null)
            .also { opened.add(it) }
        val cards = CardRepositoryImpl(CardLocalDatasource(database), CardRemoteDatasource(OkHttpClient()))
        val stops = StopsRepositoryImpl(
            StopsLocalDatasource(database),
            StopsRemoteDatasource(OkHttpClient(), JsonCache.None),
            JsonCache.None,
        )
        val settingsFile = File(folder.root, "$name-settings.json")
        val settings = SettingsStore(settingsFile, scope)
        private var ids = 0
        val backup = Backup(database, cards, stops, settings, newId = { "new-${++ids}" }, now = { 1790608530123 })
    }

    private fun TestScope.device(name: String) = Device(this, name).also { advanceUntilIdle() }

    private val card = CardEntity(
        internalId = "8d0e6f7a-1111-4c5e-9f00-000000000001",
        id = "06051868",
        prefix = "19",
        suffix = "981",
        name = "Mine",
        position = 0,
        balance = 15300.0,
        lastUpdate = 1790608530123,
    )

    private val favorite = FavoriteStop(
        id = "500800",
        name = "Plaza de Cayzedo A1",
        customName = "Trabajo",
        anchorLatitude = 3.4516,
        anchorLongitude = -76.532,
        position = 0,
        stopId = "500800",
        missingCount = 2,
        showOnHome = false,
        lines = listOf("E21"),
    )

    @Test
    fun exportsNothingWithoutCardsOrStops() = runTest {
        assertNull(device("a").backup.export())
    }

    @Test
    fun exportsVersionFourIndentedAsTheFlutterApp() = runTest {
        val device = device("a")
        device.cards.createCard(card.copy(balance = null, lastUpdate = null))

        val text = device.backup.export()!!

        val date = text.lines()[2].substringAfter("\"exportDate\": \"").substringBefore('"')
        assertEquals(1790608530123, dartTryParse(date))
        assertEquals(
            """
            {
              "version": 4,
              "exportDate": "$date",
              "cards": [
                {
                  "internalId": "8d0e6f7a-1111-4c5e-9f00-000000000001",
                  "id": "06051868",
                  "prefix": "19",
                  "suffix": "981",
                  "name": "Mine",
                  "position": 0,
                  "balance": null,
                  "lastUpdate": null
                }
              ],
              "favoriteStops": [],
              "settings": {
                "themeMode": 0,
                "farePrice": 3500,
                "homeContent": 0,
                "sortStopsByProximity": true,
                "darkMap": false,
                "sharpMap": true,
                "refreshBalancesOnOpen": true,
                "alertSound": false,
                "alertVibrate": false
              }
            }
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aBackupRestoresOnAnotherDevice() = runTest {
        val old = device("old")
        old.cards.createCard(card)
        old.stops.addFavorite(favorite)
        old.settings.setThemeMode(AppThemeMode.DARK)
        old.settings.setHomeContent(HomeContent.BOTH)
        advanceUntilIdle()

        val new = device("new")
        assertEquals(2, new.backup.import(old.backup.export()!!))

        assertEquals(listOf(card.copy(internalId = "new-1")), new.cards.getAllCards())
        // The missing count starts again
        assertEquals(listOf(favorite.copy(missingCount = 0)), new.stops.getFavorites())
        assertEquals(old.settings.settings.value, new.settings.settings.value)
        assertEquals(old.settingsFile.readText(), new.settingsFile.readText())
    }

    @Test
    fun skipsWhatIsAlreadySaved() = runTest {
        val device = device("a")
        device.cards.createCard(card)
        device.stops.addFavorite(favorite)

        assertEquals(0, device.backup.import(device.backup.export()!!))
        assertEquals(1, device.cards.getAllCards().size)
        assertEquals(1, device.stops.getFavorites().size)
    }

    @Test
    fun readsABackupTheFlutterAppWrote() = runTest {
        val device = device("a")

        val imported = device.backup.import(
            """
            {
              "version": 4,
              "exportDate": "2026-11-06T16:21:03.123456",
              "cards": [
                {"internalId": "x", "id": "06051868", "prefix": "19", "suffix": "981", "name": "Mine",
                 "position": 1, "balance": 15300.0, "lastUpdate": "2026-09-28T10:15:30.123"},
                {"internalId": "y", "id": "1", "name": "Bare"}
              ],
              "favoriteStops": [
                {"id": "station-12", "name": "Estación Alamos", "customName": null, "stopId": null,
                 "anchorLatitude": 3.48, "anchorLongitude": -76.51, "position": 2, "showOnHome": true,
                 "lines": []},
                "not a stop"
              ],
              "settings": {"themeMode": 1, "farePrice": 3500, "homeContent": 2}
            }
            """.trimIndent(),
        )

        assertEquals(3, imported)
        val cards = device.cards.getAllCards()
        assertEquals(listOf("Bare", "Mine"), cards.map { it.name })
        assertEquals(CardEntity(internalId = "new-2", id = "1", name = "Bare"), cards[0])
        assertEquals(dartTryParse("2026-09-28T10:15:30.123"), cards[1].lastUpdate)
        val stop = device.stops.getFavorites().single()
        assertTrue(stop.isArea)
        assertEquals("Estación Alamos", stop.name)
        assertEquals(AppThemeMode.LIGHT, device.settings.settings.value.themeMode)
        assertEquals(HomeContent.BOTH, device.settings.settings.value.homeContent)
    }

    @Test
    fun aVersionOneBackupCarriesOnlyCards() = runTest {
        val device = device("a")
        device.settings.setDarkMap(true)

        val imported = device.backup.import("""{"version": 1, "cards": [{"internalId": "x", "id": "1", "name": "A"}]}""")

        assertEquals(1, imported)
        assertTrue(device.settings.settings.value.darkMap)
    }

    @Test
    fun aFileWithAFaultChangesNothing() = runTest {
        val device = device("a")
        device.settings.setDarkMap(true)
        advanceUntilIdle()
        val good = """{"internalId": "x", "id": "1", "name": "A"}"""
        val stop = """{"id": "500800", "name": "A", "anchorLatitude": 3.4, "anchorLongitude": -76.5}"""

        // A mode past the last one, after a good card and stop
        assertThrowsSuspend<IndexOutOfBoundsException> {
            device.backup.import("""{"cards": [$good], "favoriteStops": [$stop], "settings": {"themeMode": 3}}""")
        }
        // A bad card after a good one
        assertThrowsSuspend<JsonTypeException> {
            device.backup.import(
                """{"cards": [$good, {"internalId": "y", "id": "2", "name": "B", "position": 1.0}]}""",
            )
        }
        // A bad stop after a good card
        assertThrowsSuspend<JsonTypeException> {
            device.backup.import(
                """{"cards": [$good], "favoriteStops": [{"id": "1", "name": "A", "anchorLatitude": "north"}]}""",
            )
        }
        assertThrowsSuspend<JsonTypeException> { device.backup.import("""{"cards": {}}""") }
        assertThrowsSuspend<JsonTypeException> { device.backup.import("[]") }
        assertThrowsSuspend<JsonFormatException> { device.backup.import("not json") }

        assertTrue(device.cards.getAllCards().isEmpty())
        assertTrue(device.stops.getFavorites().isEmpty())
        assertTrue(device.settings.settings.value.darkMap)
    }

    @Test
    fun whatAFileRepeatsIsImportedOnce() = runTest {
        val device = device("a")

        val imported = device.backup.import(
            """{"cards": [
                 {"internalId": "x", "id": "1", "name": "A"},
                 {"internalId": "y", "id": "1", "name": "A again"}],
               "favoriteStops": [
                 {"id": "1", "name": "A", "anchorLatitude": 3.4, "anchorLongitude": -76.5},
                 {"id": "1", "name": "B", "anchorLatitude": 3.4, "anchorLongitude": -76.5}]}""",
        )

        assertEquals(2, imported)
        assertEquals(listOf("A"), device.cards.getAllCards().map { it.name })
        assertEquals(listOf("A"), device.stops.getFavorites().map { it.name })
    }

    @Test
    fun theSettingsApplyOnlyOnceTheDataIsSaved() = runTest {
        val device = device("a")
        // The database refuses the stop, written after the card
        device.database.writableDatabase.execSQL(
            "CREATE TRIGGER no_stops BEFORE INSERT ON favorite_stops BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )

        assertThrowsSuspend<SQLiteConstraintException> {
            device.backup.import(
                """{"favoriteStops": [{"id": "1", "name": "A", "anchorLatitude": 3.4, "anchorLongitude": -76.5}],
                   "cards": [{"internalId": "x", "id": "1", "name": "A"}],
                   "settings": {"themeMode": 2}}""",
            )
        }

        // Nothing of it: neither the card written before the fault, nor the settings
        assertTrue(device.cards.getAllCards().isEmpty())
        assertEquals(AppThemeMode.SYSTEM, device.settings.settings.value.themeMode)
    }
}
