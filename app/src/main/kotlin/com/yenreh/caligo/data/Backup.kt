package com.yenreh.caligo.data

import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.json.cardFromJson
import com.yenreh.caligo.data.json.dartIsoString
import com.yenreh.caligo.data.json.favoriteFromJson
import com.yenreh.caligo.data.json.listOrNull
import com.yenreh.caligo.data.json.obj
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.data.json.settingsFromJson
import com.yenreh.caligo.data.json.toJson
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Backups as the Flutter app makes and reads them: JSON version 4 with
 * the cards, the favorite stops and the settings. Only the data; picking
 * and sharing the file belong to the screens.
 */
class Backup(
    private val database: AppDatabase,
    private val cards: CardRepository,
    private val stops: StopsRepository,
    private val settings: SettingsStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val imports = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Each import that went through, for the screens to read the cards
     * and stops again, as the Flutter app reloads them after one
     */
    val imported: SharedFlow<Unit> = imports.asSharedFlow()

    /**
     * Everything saved, as the text of a backup file; null when there are
     * neither cards nor stops to export
     */
    suspend fun export(): String? {
        val cards = cards.getAllCards()
        val stops = stops.getFavorites()
        if (cards.isEmpty() && stops.isEmpty()) return null

        val data = buildJsonObject {
            put("version", VERSION)
            put("exportDate", dartIsoString(now()))
            put("cards", JsonArray(cards.map { it.toJson() }))
            put("favoriteStops", JsonArray(stops.map { it.toJson() }))
            put("settings", settings.settings.value.toJson())
        }
        return indented.encodeToString(JsonElement.serializer(), data)
    }

    /**
     * Restore a backup of any version: cards and stops not saved yet, and
     * the settings when it has them. Returns how many cards and stops were
     * added.
     *
     * All or nothing, unlike the Flutter app, which kept whatever came
     * before a fault: the file is read whole first, so one that is not a
     * backup throws before anything changes; cards and stops are written
     * in one transaction, and the settings applied once it commits. A card
     * number or a stop id the file repeats is imported once.
     */
    suspend fun import(text: String): Int {
        val data = parseJson(text).obj()

        // Version 1 backups only carried the cards
        val fileCards = (data["cards"].listOrNull() ?: JsonArray(emptyList())).map { cardFromJson(it.obj()) }
        val fileStops = (data["favoriteStops"].listOrNull() ?: JsonArray(emptyList()))
            .filterIsInstance<JsonObject>()
            .map(::favoriteFromJson)
        val settingsJson = data["settings"]
        val fileSettings = if (settingsJson is JsonObject) settingsFromJson(settingsJson) else null

        // A card counts as saved by its number, prefix and suffix apart, and
        // gets a new internal id, so it never takes the place of another
        val cardIds = cards.getAllCards().map { it.id }.toMutableSet()
        val newCards = fileCards.filter { cardIds.add(it.id) }.map { it.copy(internalId = newId()) }
        val stopIds = stops.getFavorites().map { it.id }.toMutableSet()
        val newStops = fileStops.filter { stopIds.add(it.id) }

        database.inTransaction { db ->
            for (card in newCards) CardLocalDatasource.insertInto(db, card)
            for (stop in newStops) StopsLocalDatasource.insertInto(db, stop)
        }
        if (fileSettings != null) settings.applyImported(fileSettings)
        imports.tryEmit(Unit)
        return newCards.size + newStops.size
    }

    companion object {
        const val VERSION = 4

        /** Two spaces, as Dart's `JsonEncoder.withIndent('  ')` */
        @OptIn(ExperimentalSerializationApi::class)
        private val indented = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
