package com.yenreh.caligo.ui.trips

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.entities.PlaceKind
import com.yenreh.caligo.domain.entities.TripPlace

/** A place the picker chose for the planner: where the trip starts, when [origin], or ends */
data class PickedPlace(val origin: Boolean, val place: TripPlace)

/**
 * What the planner's pickers hand back down the back stack, as Flutter's
 * Navigator.pop carried a result: the place picker's [place] for the
 * planner, the map's [point] for the place picker.
 *
 * Each waits here until the screen below takes it, as that screen comes
 * back into view, since the screen under another is not composed. Saved
 * with the back stack, through rotation and process death alike, so a
 * result handed over just before either is still taken.
 */
@Stable
class PickedPlaces(place: PickedPlace? = null, point: GeoPoint? = null) {
    var place: PickedPlace? by mutableStateOf(place)
    var point: GeoPoint? by mutableStateOf(point)

    /** Take the place waiting, if any: it is handed over once */
    fun takePlace(): PickedPlace? = place.also { place = null }

    /** Take the point waiting, if any: it is handed over once */
    fun takePoint(): GeoPoint? = point.also { point = null }

    /**
     * Let go of what nobody left waits for: a result the screen below was
     * closed before it could take
     */
    fun forget(planner: Boolean, picker: Boolean) {
        if (!planner) place = null
        if (!picker) point = null
    }

    companion object {
        /** In plain values, as a saved state holds them */
        val Saver: Saver<PickedPlaces, Any> = listSaver(
            save = {
                val place = it.place
                val point = it.point
                listOf(
                    place != null,
                    place?.origin ?: false,
                    place?.place?.latitude ?: 0.0,
                    place?.place?.longitude ?: 0.0,
                    place?.place?.name.orEmpty(),
                    place?.place?.isDevice ?: false,
                    point != null,
                    point?.latitude ?: 0.0,
                    point?.longitude ?: 0.0,
                    place?.place?.kind?.key ?: PlaceKind.PLACE.key,
                    place?.place?.detail.orEmpty(),
                    place?.place?.stopId.orEmpty(),
                    place?.place?.savedId ?: 0L,
                    place?.place?.label.orEmpty(),
                )
            },
            restore = {
                PickedPlaces(
                    place = if (it[0] as Boolean) {
                        PickedPlace(
                            origin = it[1] as Boolean,
                            place = TripPlace(
                                it[2] as Double,
                                it[3] as Double,
                                it[4] as String,
                                it[5] as Boolean,
                                kind = PlaceKind.of(it[9] as String),
                                detail = (it[10] as String).ifEmpty { null },
                                stopId = (it[11] as String).ifEmpty { null },
                                savedId = (it.getOrNull(12) as? Long)?.takeIf { id -> id > 0 },
                                label = (it.getOrNull(13) as? String)?.ifEmpty { null },
                            ),
                        )
                    } else {
                        null
                    },
                    point = if (it[6] as Boolean) GeoPoint(it[7] as Double, it[8] as Double) else null,
                )
            },
        )
    }
}
