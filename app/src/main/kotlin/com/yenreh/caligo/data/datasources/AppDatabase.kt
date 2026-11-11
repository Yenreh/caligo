package com.yenreh.caligo.data.datasources

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single SQLite database shared by every local data source: the Flutter
 * app's `caligo.db`, same file, schema and upgrade chain up to version 7,
 * so the port opens what that app left as is. Version 8 adds the places
 * saved for quick plans.
 */
class AppDatabase(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCards(db)
        createFavoriteStops(db)
        createFavoritePlaces(db)
    }

    /**
     * The Flutter app's chain, step for step. From version 1 it fails as
     * there: favorite_stops is created whole, then stop_ref added again.
     * Every caligo.db was created at version 7, so none takes that path.
     *
     * Every step from 8 on must be safe to run again: a 5.1 app stamps
     * version 7 back over a database it finds newer, and the next upgrade
     * runs the step once more on what the last one left.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFavoriteStops(db)
        if (oldVersion < 3) addStopRef(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE favorite_stops ADD COLUMN custom_name TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE favorite_stops ADD COLUMN missing_count INTEGER DEFAULT 0")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE favorite_stops ADD COLUMN show_on_home INTEGER DEFAULT 1")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE favorite_stops ADD COLUMN lines TEXT")
        }
        if (oldVersion < 8) createFavoritePlaces(db)
    }

    /**
     * sqflite, without a downgrade callback, only records the version; so
     * does this. A 5.1 app, at version 7, leaves the places table in place
     * for the next upgrade to find.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Run [block] in one transaction, on one thread: everything it writes
     * is saved, or nothing when it throws
     */
    suspend fun <T> inTransaction(io: CoroutineDispatcher = Dispatchers.IO, block: (SQLiteDatabase) -> T): T =
        withContext(io) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                block(db).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }

    companion object {
        const val NAME = "caligo.db"
        const val VERSION = 8

        internal fun createCards(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE cards (
                  internal_id TEXT PRIMARY KEY,
                  card_id TEXT NOT NULL,
                  prefix TEXT,
                  suffix TEXT,
                  name TEXT NOT NULL,
                  position INTEGER DEFAULT 0,
                  balance REAL,
                  last_update INTEGER
                )
                """.trimIndent(),
            )
        }

        internal fun createFavoriteStops(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE favorite_stops (
                  stop_id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  anchor_latitude REAL NOT NULL,
                  anchor_longitude REAL NOT NULL,
                  position INTEGER DEFAULT 0,
                  stop_ref TEXT,
                  custom_name TEXT,
                  missing_count INTEGER DEFAULT 0,
                  show_on_home INTEGER DEFAULT 1,
                  lines TEXT
                )
                """.trimIndent(),
            )
        }

        /**
         * The places saved for quick plans. Only if missing: a downgrade to
         * version 7 keeps the table, and the upgrade after it finds it.
         */
        internal fun createFavoritePlaces(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS favorite_places (
                  id INTEGER PRIMARY KEY,
                  name TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  label TEXT NOT NULL,
                  detail TEXT,
                  latitude REAL NOT NULL,
                  longitude REAL NOT NULL,
                  stop_id TEXT,
                  position INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        /**
         * Favorites used to always track a single stop id. Stations span
         * several platforms and never matched one, so they become area
         * favorites: stop_ref stays null and every nearby stop is gathered.
         */
        private fun addStopRef(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE favorite_stops ADD COLUMN stop_ref TEXT")
            db.execSQL(
                "UPDATE favorite_stops SET stop_ref = stop_id " +
                    "WHERE stop_id NOT LIKE 'station-%' AND stop_id NOT LIKE 'area:%'",
            )
        }
    }
}
