package com.yenreh.caligo.data.json

import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.BusArrival
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import kotlinx.serialization.json.JsonArray
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

/** The missing count is not exported: it starts again at zero */
fun favoriteFromJson(json: JsonObject) = FavoriteStop(
    id = json["id"].string(),
    name = json["name"].string(),
    customName = json["customName"].stringOrNull(),
    stopId = json["stopId"].stringOrNull(),
    anchorLatitude = json["anchorLatitude"].double(),
    anchorLongitude = json["anchorLongitude"].double(),
    position = json["position"].longOrNull()?.toInt() ?: 0,
    showOnHome = json["showOnHome"].boolOrNull() ?: true,
    lines = json["lines"].listOrNull()?.map { it.dartString() ?: "null" } ?: emptyList(),
)

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
    isLoading = false,
)

private fun <T> List<T>.dartIndex(index: Int): T =
    getOrNull(index) ?: throw IndexOutOfBoundsException("Index $index out of range: 0..${size - 1}")
