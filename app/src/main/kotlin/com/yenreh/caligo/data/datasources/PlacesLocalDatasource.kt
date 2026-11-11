package com.yenreh.caligo.data.datasources

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.PlaceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local storage for the places saved for quick plans */
class PlacesLocalDatasource(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun <T> db(block: SQLiteDatabase.() -> T): T =
        withContext(io) { database.writableDatabase.block() }

    suspend fun getAll(): List<FavoritePlace> = db {
        query(TABLE, null, null, null, null, null, "position ASC, id ASC").use { it.toPlaces() }
    }

    /** Saved after the others; [place]'s id and position are left to storage */
    suspend fun insert(place: FavoritePlace): FavoritePlace = db {
        val position = nextPosition(this)
        val id = insertOrThrow(TABLE, null, place.copy(position = position).toValues())
        place.copy(id = id, position = position)
    }

    suspend fun rename(id: Long, name: String) {
        db { update(TABLE, ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString())) }
    }

    suspend fun delete(id: Long) {
        db { delete(TABLE, "id = ?", arrayOf(id.toString())) }
    }

    private fun Cursor.toPlaces(): List<FavoritePlace> {
        val id = getColumnIndexOrThrow("id")
        val name = getColumnIndexOrThrow("name")
        val kind = getColumnIndexOrThrow("kind")
        val label = getColumnIndexOrThrow("label")
        val detail = getColumnIndexOrThrow("detail")
        val latitude = getColumnIndexOrThrow("latitude")
        val longitude = getColumnIndexOrThrow("longitude")
        val stopId = getColumnIndexOrThrow("stop_id")
        val position = getColumnIndexOrThrow("position")
        return buildList {
            while (moveToNext()) {
                add(
                    FavoritePlace(
                        id = getLong(id),
                        name = getString(name),
                        kind = PlaceKind.of(getString(kind)),
                        label = getString(label),
                        detail = if (isNull(detail)) null else getString(detail),
                        latitude = getDouble(latitude),
                        longitude = getDouble(longitude),
                        stopId = if (isNull(stopId)) null else getString(stopId),
                        position = getInt(position),
                    ),
                )
            }
        }
    }

    companion object {
        private const val TABLE = "favorite_places"

        /** Insert [place] through [db] after the others, as a backup brings it: its id is left to storage */
        internal fun insertInto(db: SQLiteDatabase, place: FavoritePlace) {
            db.insertOrThrow(TABLE, null, place.copy(position = nextPosition(db)).toValues())
        }

        private fun nextPosition(db: SQLiteDatabase): Int =
            db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM $TABLE", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }

        private fun FavoritePlace.toValues() = ContentValues().apply {
            put("name", name)
            put("kind", kind.key)
            put("label", label)
            put("detail", detail)
            put("latitude", latitude)
            put("longitude", longitude)
            put("stop_id", stopId)
            put("position", position)
        }
    }
}
