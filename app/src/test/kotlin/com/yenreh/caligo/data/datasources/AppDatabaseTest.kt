package com.yenreh.caligo.data.datasources

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database the Flutter app leaves, at each version it went through,
 * opens as a fresh one. The schemas are the app's own, from its history.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A database file as the Flutter app at [version] created it */
    private fun flutterDatabase(name: String, version: Int, vararg rows: String) {
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(CARDS)
            if (version >= 2) db.execSQL(FAVORITE_STOPS.getValue(version))
            rows.forEach(db::execSQL)
            db.version = version
        }
    }

    /** Columns and indexes of every table, as SQLite reports them */
    private fun SQLiteDatabase.schema(): Map<String, List<String>> {
        val tables = rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name != 'android_metadata' " +
                "ORDER BY name",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            val columns = rawQuery("PRAGMA table_info($table)", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
                    }
                }
            }
            val indexes = rawQuery("PRAGMA index_list($table)", null).use { c ->
                buildList { while (c.moveToNext()) add("index " + c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            columns + indexes
        }
    }

    /** Every row of every table, as text */
    private fun SQLiteDatabase.rows(): Map<String, List<String>> = schema().keys.associateWith { table ->
        rawQuery("SELECT * FROM $table", null).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
            }
        }
    }

    private fun fresh(): Map<String, List<String>> =
        AppDatabase(context, "fresh.db").use { it.writableDatabase.schema() }

    @Test
    fun aFreshDatabaseHasTheFlutterSchemaWithThePlaces() {
        AppDatabase(context).use { helper ->
            val db = helper.writableDatabase
            assertEquals(8, db.version)
            assertTrue(db.path.endsWith("/databases/caligo.db"))
            assertEquals(
                listOf(
                    "0|stop_id|TEXT|0|null|1",
                    "1|name|TEXT|1|null|0",
                    "2|anchor_latitude|REAL|1|null|0",
                    "3|anchor_longitude|REAL|1|null|0",
                    "4|position|INTEGER|0|0|0",
                    "5|stop_ref|TEXT|0|null|0",
                    "6|custom_name|TEXT|0|null|0",
                    "7|missing_count|INTEGER|0|0|0",
                    "8|show_on_home|INTEGER|0|1|0",
                    "9|lines|TEXT|0|null|0",
                    "index sqlite_autoindex_favorite_stops_1",
                ),
                db.schema()["favorite_stops"],
            )
            assertEquals(
                listOf(
                    "0|id|INTEGER|0|null|1",
                    "1|name|TEXT|1|null|0",
                    "2|kind|TEXT|1|null|0",
                    "3|label|TEXT|1|null|0",
                    "4|detail|TEXT|0|null|0",
                    "5|latitude|REAL|1|null|0",
                    "6|longitude|REAL|1|null|0",
                    "7|stop_id|TEXT|0|null|0",
                    "8|position|INTEGER|1|0|0",
                ),
                db.schema()["favorite_places"],
            )
        }
    }

    @Test
    fun everyVersionFromTwoUpgradesToAFreshDatabase() {
        val fresh = fresh()
        for (version in 2..7) {
            val name = "v$version.db"
            flutterDatabase(name, version)
            AppDatabase(context, name).use { helper ->
                val db = helper.writableDatabase
                assertEquals("version $version", 8, db.version)
                assertEquals("version $version", fresh, db.schema())
            }
        }
    }

    @Test
    fun upgradingKeepsTheRowsAndTurnsStationsIntoAreas() {
        flutterDatabase(
            "v2.db",
            2,
            "INSERT INTO cards (internal_id, card_id, name, balance) VALUES ('i1', '06051868', 'Mine', 15300.0)",
            "INSERT INTO favorite_stops VALUES ('500800', 'Plaza de Cayzedo A1', 3.45, -76.53, 0)",
            "INSERT INTO favorite_stops VALUES ('station-12', 'Estación Alamos', 3.48, -76.51, 1)",
            "INSERT INTO favorite_stops VALUES ('area:3.45,-76.53', 'Centro', 3.45, -76.53, 2)",
        )
        AppDatabase(context, "v2.db").use { helper ->
            val db = helper.writableDatabase
            val rows = db.rawQuery(
                "SELECT stop_id, stop_ref, custom_name, missing_count, show_on_home, lines " +
                    "FROM favorite_stops ORDER BY position",
                null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
                    }
                }
            }
            assertEquals(
                listOf(
                    "500800|500800|null|0|1|null",
                    "station-12|null|null|0|1|null",
                    "area:3.45,-76.53|null|null|0|1|null",
                ),
                rows,
            )
            db.rawQuery("SELECT balance FROM cards", null).use {
                it.moveToFirst()
                assertEquals(15300.0, it.getDouble(0), 0.0)
            }
        }
    }

    /**
     * Kept from the Flutter app, as a doubt for review: step 2 creates the
     * whole current table, so step 3 adds stop_ref twice and the upgrade
     * fails, rolled back. Every caligo.db was created at version 7.
     */
    @Test
    fun versionOneFailsToUpgradeAsInTheFlutterApp() {
        flutterDatabase("v1.db", 1, "INSERT INTO cards (internal_id, card_id, name) VALUES ('i1', '1', 'Mine')")

        AppDatabase(context, "v1.db").use { helper ->
            try {
                helper.writableDatabase
                fail("The upgrade from version 1 was expected to fail")
            } catch (e: SQLiteException) {
                assertTrue(e.message, e.message!!.contains("duplicate column name: stop_ref"))
            }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("v1.db").path, null, 0).use { db ->
            assertEquals(1, db.version)
            assertNull(db.schema()["favorite_stops"])
            assertEquals(1, db.schema().getValue("cards").count { it.contains("internal_id") })
        }
    }

    @Test
    fun aNewerDatabaseOpensAsSqfliteLeftIt() {
        AppDatabase(context, "newer.db").use { it.writableDatabase.version = 9 }

        AppDatabase(context, "newer.db").use { assertEquals(8, it.writableDatabase.version) }
    }

    /** 5.1's database, at version 7 with its cards and stops: the places come, and nothing else changes */
    @Test
    fun versionSevenGainsThePlacesKeepingItsRows() {
        flutterDatabase(
            "v7.db",
            7,
            "INSERT INTO cards (internal_id, card_id, name, position, balance, last_update) " +
                "VALUES ('i1', '06051868', 'Mine', 0, 15300.0, 1790608530123)",
            "INSERT INTO favorite_stops VALUES ('500800', 'Plaza de Cayzedo A1', 3.45, -76.53, 0, '500800', 'Trabajo', 2, 0, 'E21,T31')",
            "INSERT INTO favorite_stops VALUES ('area:3.45,-76.53', 'Centro', 3.45, -76.53, 1, null, null, 0, 1, null)",
        )
        val before = SQLiteDatabase.openDatabase(context.getDatabasePath("v7.db").path, null, 0).use { it.rows() }

        AppDatabase(context, "v7.db").use { helper ->
            val db = helper.writableDatabase
            assertEquals(8, db.version)
            assertEquals(before, db.rows() - "favorite_places")
            assertEquals(emptyList<String>(), db.rows().getValue("favorite_places"))
            db.execSQL("INSERT INTO favorite_places (name, kind, label, latitude, longitude) VALUES ('Casa', 'address', 'Cl 5', 3.4, -76.5)")
        }
    }

    /** Back on 5.1, at version 7 again, the places stay; the next upgrade finds them */
    @Test
    fun anUpgradeAfterADowngradeKeepsThePlaces() {
        AppDatabase(context, "back.db").use {
            it.writableDatabase.execSQL(
                "INSERT INTO favorite_places (name, kind, label, latitude, longitude) VALUES ('Casa', 'address', 'Cl 5', 3.4, -76.5)",
            )
            it.writableDatabase.version = 7
        }

        AppDatabase(context, "back.db").use { helper ->
            val db = helper.writableDatabase
            assertEquals(8, db.version)
            assertEquals(listOf("1|Casa|address|Cl 5|null|3.4|-76.5|null|0"), db.rows().getValue("favorite_places"))
        }
    }

    private companion object {
        /** The cards table never changed */
        const val CARDS = """
            CREATE TABLE cards (
              internal_id TEXT PRIMARY KEY,
              card_id TEXT NOT NULL,
              prefix TEXT,
              suffix TEXT,
              name TEXT NOT NULL,
              position INTEGER DEFAULT 0,
              balance REAL,
              last_update INTEGER
            )"""

        private const val V2_COLUMNS = """
              stop_id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              anchor_latitude REAL NOT NULL,
              anchor_longitude REAL NOT NULL,
              position INTEGER DEFAULT 0"""

        /** favorite_stops as each version created it */
        val FAVORITE_STOPS = mapOf(
            2 to "CREATE TABLE favorite_stops ($V2_COLUMNS)",
            3 to "CREATE TABLE favorite_stops ($V2_COLUMNS, stop_ref TEXT)",
            4 to "CREATE TABLE favorite_stops ($V2_COLUMNS, stop_ref TEXT, custom_name TEXT)",
            5 to "CREATE TABLE favorite_stops ($V2_COLUMNS, stop_ref TEXT, custom_name TEXT, " +
                "missing_count INTEGER DEFAULT 0)",
            6 to "CREATE TABLE favorite_stops ($V2_COLUMNS, stop_ref TEXT, custom_name TEXT, " +
                "missing_count INTEGER DEFAULT 0, show_on_home INTEGER DEFAULT 1)",
            7 to "CREATE TABLE favorite_stops ($V2_COLUMNS, stop_ref TEXT, custom_name TEXT, " +
                "missing_count INTEGER DEFAULT 0, show_on_home INTEGER DEFAULT 1, lines TEXT)",
        )
    }
}
