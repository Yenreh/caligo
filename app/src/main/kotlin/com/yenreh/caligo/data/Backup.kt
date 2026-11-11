package com.yenreh.caligo.data

import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.PlacesLocalDatasource
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.json.cardFromJson
import com.yenreh.caligo.data.json.dartIsoString
import com.yenreh.caligo.data.json.favoriteFromJson
import com.yenreh.caligo.data.json.favoritePlaceFromJson
import com.yenreh.caligo.data.json.listOrNull
import com.yenreh.caligo.data.json.obj
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.data.json.settingsFromJson
import com.yenreh.caligo.data.json.toJson
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.matching
import com.yenreh.caligo.domain.repositories.CardRepository
import com.yenreh.caligo.domain.repositories.PlacesRepository
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
 * the cards, the favorite stops and the settings, and since 5.2 the
 * places saved for quick plans under a key of their own. Still version
 * 4: every import since the Flutter app reads the keys it knows and
 * leaves the rest, so an older app takes such a file without its places.
 * Only the data; picking and sharing the file belong to the screens.
 */
class Backup(
    private val database: AppDatabase,
    private val cards: CardRepository,
    private val stops: StopsRepository,
    private val settings: SettingsStore,
    private val places: PlacesRepository,
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
     * no cards, stops or places to export
     */
    suspend fun export(): String? {
        val cards = cards.getAllCards()
        val stops = stops.getFavorites()
        places.load()
        val saved = places.places.value
        if (cards.isEmpty() && stops.isEmpty() && saved.isEmpty()) return null

        val data = buildJsonObject {
            put("version", VERSION)
            put("exportDate", dartIsoString(now()))
            put("cards", JsonArray(cards.map { it.toJson() }))
            put("favoriteStops", JsonArray(stops.map { it.toJson() }))
            put("favoritePlaces", JsonArray(saved.map { it.toJson() }))
            put("settings", settings.settings.value.toJson())
        }
        return indented.encodeToString(JsonElement.serializer(), data)
    }

    /**
     * Restore a backup of any version: cards, stops and places not saved
     * yet, and the settings when it has them. Returns how many cards,
     * stops and places were added.
     *
     * All or nothing, unlike the Flutter app, which kept whatever came
     * before a fault: the file is read whole first, so one that is not a
     * backup throws before anything changes; cards and stops are written
     * in one transaction, and the settings applied once it commits. A card
     * number or a stop id the file repeats is imported once; so is a place
     * the star would show as saved, by its stop or where it is.
     */
    suspend fun import(text: String): Int {
        val data = parseJson(text).obj()

        // Version 1 backups only carried the cards
        val fileCards = (data["cards"].listOrNull() ?: JsonArray(emptyList())).map { cardFromJson(it.obj()) }
        val fileStops = (data["favoriteStops"].listOrNull() ?: JsonArray(emptyList()))
            .filterIsInstance<JsonObject>()
            .map(::favoriteFromJson)
        // Before 5.2 backups carried no places
        val filePlaces = (data["favoritePlaces"].listOrNull() ?: JsonArray(emptyList()))
            .filterIsInstance<JsonObject>()
            .map(::favoritePlaceFromJson)
            .sortedBy { it.position }
        val settingsJson = data["settings"]
        val fileSettings = if (settingsJson is JsonObject) settingsFromJson(settingsJson) else null

        // A card counts as saved by its number, prefix and suffix apart, and
        // gets a new internal id, so it never takes the place of another
        val cardIds = cards.getAllCards().map { it.id }.toMutableSet()
        val newCards = fileCards.filter { cardIds.add(it.id) }.map { it.copy(internalId = newId()) }
        val stopIds = stops.getFavorites().map { it.id }.toMutableSet()
        val newStops = fileStops.filter { stopIds.add(it.id) }
        places.load()
        val knownPlaces = places.places.value.toMutableList<FavoritePlace>()
        val newPlaces = filePlaces.filter { place ->
            (knownPlaces.matching(place.latitude, place.longitude, place.stopId) == null).also { if (it) knownPlaces.add(place) }
        }

        database.inTransaction { db ->
            for (card in newCards) CardLocalDatasource.insertInto(db, card)
            for (stop in newStops) StopsLocalDatasource.insertInto(db, stop)
            for (place in newPlaces) PlacesLocalDatasource.insertInto(db, place)
        }
        places.load()
        if (fileSettings != null) settings.applyImported(fileSettings)
        imports.tryEmit(Unit)
        return newCards.size + newStops.size + newPlaces.size
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
