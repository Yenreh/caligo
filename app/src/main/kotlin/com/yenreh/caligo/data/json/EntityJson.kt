package com.yenreh.caligo.data.json

import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.domain.entities.cutAt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The entities in the JSON the Flutter app wrote: backups (version 4),
 * settings.json and the kept arrivals. Same keys, order and types.
 */

fun CardEntity.toJson(): JsonObject = buildJsonObject {
    put("internalId", internalId)
    put("id", id)
    put("prefix", prefix)
    put("suffix", suffix)
    put("name", name)
    put("position", position)
    put("balance", balance)
    put("lastUpdate", lastUpdate?.let(::dartIsoString))
}

fun cardFromJson(json: JsonObject): CardEntity {
    val lastUpdate = json["lastUpdate"]
    return CardEntity(
        internalId = json["internalId"].string(),
        id = json["id"].string(),
        prefix = json["prefix"].stringOrNull() ?: "",
        suffix = json["suffix"].stringOrNull() ?: "",
        name = json["name"].string(),
        position = json["position"].intOrNull() ?: 0,
        balance = json["balance"].doubleOrNull(),
        lastUpdate = if (lastUpdate == null || lastUpdate is JsonNull) {
            null
        } else {
            dartTryParse(lastUpdate.string())
        },
    )
}

fun FavoriteStop.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("customName", customName)
    put("stopId", stopId)
    put("anchorLatitude", anchorLatitude)
    put("anchorLongitude", anchorLongitude)
    put("position", position)
    put("showOnHome", showOnHome)
    put("lines", JsonArray(lines.map(::JsonPrimitive)))
}

/**
 * The missing count is not exported: it starts again at zero. Unlike the
 * Flutter app, a point off the map is a fault, and its texts and lines are
 * capped: a crafted file could otherwise stop every later export or read.
 */
fun favoriteFromJson(json: JsonObject) = FavoriteStop(
    id = json["id"].string().cutAt(MAX_TEXT),
    name = json["name"].string().cutAt(MAX_TEXT),
    customName = json["customName"].stringOrNull()?.cutAt(MAX_TEXT),
    stopId = json["stopId"].stringOrNull()?.cutAt(MAX_TEXT),
    anchorLatitude = json["anchorLatitude"].latitude(),
    anchorLongitude = json["anchorLongitude"].longitude(),
    position = json["position"].position(),
    showOnHome = json["showOnHome"].boolOrNull() ?: true,
    lines = json["lines"].listOrNull()?.take(MAX_LINES)?.map { (it.dartString() ?: "null").cutAt(MAX_TEXT) } ?: emptyList(),
)

/** A saved place in a backup, a key the Flutter app never wrote; its id is the device's own */
fun FavoritePlace.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("kind", kind.key)
    put("label", label)
    put("detail", detail)
    put("latitude", latitude)
    put("longitude", longitude)
    put("stopId", stopId)
    put("position", position)
}

/**
 * A saved place from a backup, its name cleaned as one typed and its texts
 * capped. A blank name falls back on the label's first words, as the save
 * sheet suggests them; one with neither, or off the map, is a fault.
 */
fun favoritePlaceFromJson(json: JsonObject): FavoritePlace {
    val name = json["name"].string()
    val label = (json["label"].stringOrNull() ?: name).cutAt(MAX_TEXT)
    return FavoritePlace(
        id = 0,
        name = FavoritePlace.cleanName(name)
            ?: FavoritePlace.cleanName(FavoritePlace.suggestedName(label))
            ?: throw JsonTypeException("Expected a name"),
        kind = PlaceKind.of(json["kind"].stringOrNull()),
        label = label,
        detail = json["detail"].stringOrNull()?.cutAt(MAX_TEXT),
        latitude = json["latitude"].latitude(),
        longitude = json["longitude"].longitude(),
        stopId = json["stopId"].stringOrNull()?.cutAt(MAX_TEXT),
        position = json["position"].position(),
    )
}

/** The longest text a stop or a place keeps from a backup: a name, a label, an id */
const val MAX_TEXT = 200

/** The most lines a stop keeps from a backup, more than any station serves */
const val MAX_LINES = 100

/** A latitude: a finite number within ±90; anything else is a fault */
private fun JsonElement?.latitude(): Double =
    double().also { if (it !in -90.0..90.0) throw JsonTypeException("Expected a latitude, found $it") }

/** A longitude: a finite number within ±180; anything else is a fault */
private fun JsonElement?.longitude(): Double =
    double().also { if (it !in -180.0..180.0) throw JsonTypeException("Expected a longitude, found $it") }

/**
 * A stop's or a place's position, as `(json['position'] as num?)?.toInt() ?? 0`
 * reads it: a double is truncated, and a number past 32 bits held at the
 * nearest end, so the order stays; anything else is a fault
 */
private fun JsonElement?.position(): Int =
    longOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: 0

fun BusArrival.toJson(): JsonObject = buildJsonObject {
    put("line", line)
    put("destination", destination)
    put("arrivalTime", arrivalTime)
    put("vehicleId", vehicleId)
    put("stopName", stopName)
}

fun busFromJson(json: JsonObject) = BusArrival(
    line = json["line"].string(),
    destination = json["destination"].string(),
    arrivalTime = json["arrivalTime"].long(),
    vehicleId = json["vehicleId"].stringOrNull() ?: "",
    stopName = json["stopName"].stringOrNull() ?: "",
)

fun Settings.toJson(): JsonObject = buildJsonObject {
    put("themeMode", themeMode.ordinal)
    put("farePrice", farePrice)
    put("homeContent", homeContent.ordinal)
    put("sortStopsByProximity", sortStopsByProximity)
    put("darkMap", darkMap)
    put("sharpMap", sharpMap)
    put("refreshBalancesOnOpen", refreshBalancesOnOpen)
    put("alertSound", alertSound)
    put("alertVibrate", alertVibrate)
}

/**
 * Settings from [json], defaults for what it lacks. A value of the wrong
 * type, or a mode past the last one, throws as in Dart.
 */
fun settingsFromJson(json: JsonObject) = Settings(
    themeMode = AppThemeMode.entries.dartIndex(json["themeMode"].intOrNull() ?: 0),
    farePrice = json["farePrice"].intOrNull() ?: Settings.DEFAULT_FARE_PRICE,
    homeContent = HomeContent.entries.dartIndex(json["homeContent"].intOrNull() ?: 0),
    sortStopsByProximity = json["sortStopsByProximity"].boolOrNull() ?: true,
    darkMap = json["darkMap"].boolOrNull() ?: false,
    sharpMap = json["sharpMap"].boolOrNull() ?: true,
    refreshBalancesOnOpen = json["refreshBalancesOnOpen"].boolOrNull() ?: true,
    alertSound = json["alertSound"].boolOrNull() ?: false,
    alertVibrate = json["alertVibrate"].boolOrNull() ?: false,
    isLoading = false,
)

private fun <T> List<T>.dartIndex(index: Int): T =
    getOrNull(index) ?: throw IndexOutOfBoundsException("Index $index out of range: 0..${size - 1}")
