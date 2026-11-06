package com.yenreh.caligo.data.datasources

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database shared by every local data source: the Flutter
 * app's `caligo.db`, same file, schema, version and upgrade chain, so the
 * port opens what that app left as is.
 */
class AppDatabase(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCards(db)
        createFavoriteStops(db)
    }

    /**
     * The Flutter app's chain, step for step. From version 1 it fails as
     * there: favorite_stops is created whole, then stop_ref added again.
     * Every caligo.db was created at version 7, so none takes that path.
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
    }

    /** sqflite, without a downgrade callback, only records the version */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val NAME = "caligo.db"
        const val VERSION = 7

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
