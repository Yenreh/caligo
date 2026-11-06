package com.yenreh.caligo.data

import com.yenreh.caligo.data.json.cardFromJson
import com.yenreh.caligo.data.json.dartIsoString
import com.yenreh.caligo.data.json.favoriteFromJson
import com.yenreh.caligo.data.json.listOrNull
import com.yenreh.caligo.data.json.obj
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.data.json.toJson
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.StopsRepository
import java.util.UUID
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
    private val cards: CardRepository,
    private val stops: StopsRepository,
    private val settings: SettingsStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
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
     * Throws on a file that is not a backup, as the Flutter app failed on
     * it; whatever came before the fault stays imported, as there.
     */
    suspend fun import(text: String): Int {
        val data = parseJson(text).obj()

        // Version 1 backups only carried the cards
        val cardsList = data["cards"].listOrNull() ?: JsonArray(emptyList())
        val stopsList = data["favoriteStops"].listOrNull() ?: JsonArray(emptyList())
        var imported = 0

        // A card counts as saved by its number, prefix and suffix apart
        val saved = cards.getAllCards()
        for (cardJson in cardsList) {
            val card = cardFromJson(cardJson.obj())
            if (saved.none { it.id == card.id }) {
                // A new internal id, so it never takes the place of another
                cards.createCard(card.copy(internalId = newId()))
                imported++
            }
        }

        val settingsJson = data["settings"]
        if (settingsJson is JsonObject) settings.importSettings(settingsJson)

        imported += importFavorites(stopsList.filterIsInstance<JsonObject>().map(::favoriteFromJson))
        return imported
    }

    /** Save [favorites], skipping the ones already saved */
    suspend fun importFavorites(favorites: List<FavoriteStop>): Int {
        val existing = stops.getFavorites().map { it.id }.toSet()
        var imported = 0
        for (stop in favorites) {
            if (stop.id in existing) continue
            stops.addFavorite(stop)
            imported++
        }
        return imported
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
