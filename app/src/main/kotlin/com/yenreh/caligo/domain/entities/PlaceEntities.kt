package com.yenreh.caligo.domain.entities

import com.yenreh.caligo.domain.metersBetween

/** What a place picked for the planner is */
enum class PlaceKind {
    ADDRESS,
    PLACE,
    STOP,
    ;

    /** As storage and backups write it */
    val key: String get() = name.lowercase()

    companion object {
        /** A kind by its [key]; an unknown one is a plain place */
        fun of(key: String?): PlaceKind = entries.firstOrNull { it.key == key } ?: PLACE
    }
}

/**
 * A place saved for quick plans under a short [name] of the user's own,
 * with what it was called where it was picked ([label] and [detail]) and
 * what plans to it again: where it is and, for a stop, its [stopId].
 * Listed by [position], the order saved.
 */
data class FavoritePlace(
    val id: Long,
    val name: String,
    val kind: PlaceKind,
    val label: String,
    val detail: String?,
    val latitude: Double,
    val longitude: Double,
    val stopId: String?,
    val position: Int,
) {
    /** As the planner takes it, under its own name, followed by [id] */
    val tripPlace: TripPlace
        get() = TripPlace(latitude, longitude, name, kind = kind, detail = detail, stopId = stopId, savedId = id, label = label)

    /**
     * Whether it is the stop [stopId], when both are stops with an id;
     * otherwise whether it is within [SAME_PLACE_METERS] of [latitude],
     * [longitude], as a stop saved without its id
     */
    fun isAt(latitude: Double, longitude: Double, stopId: String?): Boolean =
        if (stopId != null && this.stopId != null) {
            this.stopId == stopId
        } else {
            metersBetween(this.latitude, this.longitude, latitude, longitude) <= SAME_PLACE_METERS
        }

    companion object {
        /** Long enough for "Casa de mi mamá", short enough for a chip */
        const val MAX_NAME = 24

        /** Two picks this close are the same place: a geocoder's answers move a little */
        const val SAME_PLACE_METERS = 40.0

        private val SPACES = Regex("\\s+")

        /**
         * [raw] as a name: one space between words, [MAX_NAME] at most, as
         * the name box counts them, never half an emoji; null when nothing is left
         */
        fun cleanName(raw: String): String? =
            raw.replace(SPACES, " ").trim().cutAt(MAX_NAME).trimEnd().ifEmpty { null }

        /** What the name box starts with for a place called [label]: whole words up to [MAX_NAME] */
        fun suggestedName(label: String): String {
            val clean = label.replace(SPACES, " ").trim()
            if (clean.length <= MAX_NAME) return clean
            val cut = clean.take(MAX_NAME + 1).lastIndexOf(' ')
            return if (cut > 0) clean.take(cut).trimEnd(',', ' ') else clean.cutAt(MAX_NAME)
        }
    }
}

/** The saved place [place] is: by its stop for a stop, by where it is otherwise */
fun List<FavoritePlace>.matching(place: TripPlace): FavoritePlace? =
    if (place.isDevice) null else matching(place.latitude, place.longitude, place.stopId)

fun List<FavoritePlace>.matching(latitude: Double, longitude: Double, stopId: String?): FavoritePlace? =
    filter { it.isAt(latitude, longitude, stopId) }
        .minByOrNull { metersBetween(it.latitude, it.longitude, latitude, longitude) }

/** At most [max] UTF-16 units, one fewer rather than half a surrogate pair */
fun String.cutAt(max: Int): String =
    if (length <= max) this else take(if (Character.isHighSurrogate(this[max - 1])) max - 1 else max)
