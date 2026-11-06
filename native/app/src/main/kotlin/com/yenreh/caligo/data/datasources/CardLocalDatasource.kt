package com.yenreh.caligo.data.datasources

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.yenreh.caligo.domain.entities.CardEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The saved cards, in SQLite */
class CardLocalDatasource(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun <T> db(block: SQLiteDatabase.() -> T): T =
        withContext(io) { database.writableDatabase.block() }

    suspend fun getAllCards(): List<CardEntity> = db {
        query(TABLE, null, null, null, null, null, "position ASC, name ASC").use { it.toCards() }
    }

    suspend fun getCardById(cardId: String): CardEntity? = db {
        query(TABLE, null, "card_id = ?", arrayOf(cardId), null, null, null)
            .use { it.toCards().firstOrNull() }
    }

    /** Insert a card, replacing one with the same internal id */
    suspend fun insertCard(card: CardEntity) {
        db { insertWithOnConflict(TABLE, null, card.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    suspend fun updateCard(card: CardEntity) {
        db { update(TABLE, card.toValues(), "internal_id = ?", arrayOf(card.internalId)) }
    }

    suspend fun deleteCard(cardId: String) {
        db { delete(TABLE, "card_id = ?", arrayOf(cardId)) }
    }

    suspend fun updateCardBalance(cardId: String, balance: Double, lastUpdate: Long) {
        val values = ContentValues().apply {
            put("balance", balance)
            put("last_update", lastUpdate)
        }
        db { update(TABLE, values, "card_id = ?", arrayOf(cardId)) }
    }

    private fun Cursor.toCards(): List<CardEntity> {
        val internalId = getColumnIndexOrThrow("internal_id")
        val cardId = getColumnIndexOrThrow("card_id")
        val prefix = getColumnIndexOrThrow("prefix")
        val suffix = getColumnIndexOrThrow("suffix")
        val name = getColumnIndexOrThrow("name")
        val position = getColumnIndexOrThrow("position")
        val balance = getColumnIndexOrThrow("balance")
        val lastUpdate = getColumnIndexOrThrow("last_update")
        return buildList {
            while (moveToNext()) {
                add(
                    CardEntity(
                        internalId = getString(internalId),
                        id = getString(cardId),
                        prefix = if (isNull(prefix)) "" else getString(prefix),
                        suffix = if (isNull(suffix)) "" else getString(suffix),
                        name = getString(name),
                        position = if (isNull(position)) 0 else getInt(position),
                        balance = if (isNull(balance)) null else getDouble(balance),
                        lastUpdate = if (isNull(lastUpdate)) null else getLong(lastUpdate),
                    ),
                )
            }
        }
    }

    private fun CardEntity.toValues() = ContentValues().apply {
        put("internal_id", internalId)
        put("card_id", id)
        put("prefix", prefix)
        put("suffix", suffix)
        put("name", name)
        put("position", position)
        put("balance", balance)
        put("last_update", lastUpdate)
    }

    private companion object {
        const val TABLE = "cards"
    }
}
