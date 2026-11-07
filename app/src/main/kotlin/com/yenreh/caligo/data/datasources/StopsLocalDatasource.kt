package com.yenreh.caligo.data.datasources

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.yenreh.caligo.domain.entities.FavoriteStop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local storage for the stops the user saved */
class StopsLocalDatasource(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun <T> db(block: SQLiteDatabase.() -> T): T =
        withContext(io) { database.writableDatabase.block() }

    suspend fun getFavorites(): List<FavoriteStop> = db {
        query(TABLE, null, null, null, null, null, "position ASC, name ASC").use { it.toStops() }
    }

    /** Throws when the id is already saved, as sqflite's insert did */
    suspend fun insert(stop: FavoriteStop) {
        db { insertInto(this, stop) }
    }

    suspend fun update(stop: FavoriteStop) {
        db { update(TABLE, stop.toValues(), "stop_id = ?", arrayOf(stop.id)) }
    }

    suspend fun delete(stopId: String) {
        db { delete(TABLE, "stop_id = ?", arrayOf(stopId)) }
    }

    suspend fun exists(stopId: String): Boolean = db {
        query(TABLE, null, "stop_id = ?", arrayOf(stopId), null, null, null, "1").use { it.count > 0 }
    }

    suspend fun nextPosition(): Int = db {
        rawQuery("SELECT COALESCE(MAX(position), -1) + 1 AS next FROM $TABLE", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else 0
        }
    }

    private fun Cursor.toStops(): List<FavoriteStop> {
        val stopId = getColumnIndexOrThrow("stop_id")
        val name = getColumnIndexOrThrow("name")
        val latitude = getColumnIndexOrThrow("anchor_latitude")
        val longitude = getColumnIndexOrThrow("anchor_longitude")
        val position = getColumnIndexOrThrow("position")
        val stopRef = getColumnIndexOrThrow("stop_ref")
        val customName = getColumnIndexOrThrow("custom_name")
        val missingCount = getColumnIndexOrThrow("missing_count")
        val showOnHome = getColumnIndexOrThrow("show_on_home")
        val lines = getColumnIndexOrThrow("lines")
        return buildList {
            while (moveToNext()) {
                add(
                    FavoriteStop(
                        id = getString(stopId),
                        name = getString(name),
                        anchorLatitude = getDouble(latitude),
                        anchorLongitude = getDouble(longitude),
                        position = if (isNull(position)) 0 else getInt(position),
                        stopId = if (isNull(stopRef)) null else getString(stopRef),
                        customName = if (isNull(customName)) null else getString(customName),
                        missingCount = if (isNull(missingCount)) 0 else getInt(missingCount),
                        showOnHome = isNull(showOnHome) || getInt(showOnHome) != 0,
                        // Line short names never hold a comma
                        lines = if (isNull(lines)) {
                            emptyList()
                        } else {
                            getString(lines).split(',').filter { it.isNotEmpty() }
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val TABLE = "favorite_stops"

        /** Insert [stop] through [db]; throws when its id is already saved */
        internal fun insertInto(db: SQLiteDatabase, stop: FavoriteStop) {
            db.insertOrThrow(TABLE, null, stop.toValues())
        }

        private fun FavoriteStop.toValues() = ContentValues().apply {
            put("stop_id", id)
            put("name", name)
            put("anchor_latitude", anchorLatitude)
            put("anchor_longitude", anchorLongitude)
            put("position", position)
            put("stop_ref", stopId)
            put("custom_name", customName)
            put("missing_count", missingCount)
            put("show_on_home", if (showOnHome) 1 else 0)
            put("lines", if (lines.isEmpty()) null else lines.joinToString(","))
        }
    }
}
