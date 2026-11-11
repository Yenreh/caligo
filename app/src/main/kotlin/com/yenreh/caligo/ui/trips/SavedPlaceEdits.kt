package com.yenreh.caligo.ui.trips

import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.repositories.PlacesRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The saved places as the planner and the place picker change them, on
 * [scope]: each change in the background, and [failed] for the snackbar
 * when one does not go through. What changed shows through the
 * repository's places, which both screens follow.
 */
internal class SavedPlaceEdits(
    private val places: PlacesRepository,
    private val scope: CoroutineScope,
    private val failed: () -> Unit,
) {
    /** Read them from storage, quietly: without them the screen works all the same */
    fun load() {
        scope.launch {
            try {
                places.load()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun add(name: String, place: TripPlace) = change { places.add(name, place) }

    /** Rename the place [id]; a name with nothing left once cleaned is no rename */
    fun rename(id: Long, name: String) {
        if (FavoritePlace.cleanName(name) != null) change { places.rename(id, name) }
    }

    fun delete(id: Long) = change { places.delete(id) }

    private fun change(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed()
            }
        }
    }
}
