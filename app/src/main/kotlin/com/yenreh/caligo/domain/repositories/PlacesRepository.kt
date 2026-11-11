package com.yenreh.caligo.domain.repositories

import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import kotlinx.coroutines.flow.StateFlow

/**
 * The places saved for quick plans, in the order saved.
 *
 * The one repository that holds what it read as a [StateFlow]: the
 * planner's chips and stars and the place picker over it show the same
 * places at once, so a place renamed or deleted in the picker is already
 * so in the planner below, which follows it in its fields.
 */
interface PlacesRepository {
    /** What is saved, as last read or written; empty until [load] */
    val places: StateFlow<List<FavoritePlace>>

    /** Read them again from storage, into [places] */
    suspend fun load()

    /** What is saved, read from storage now, as a backup takes it */
    suspend fun all(): List<FavoritePlace>

    /** Save [place] under [name], cleaned as [FavoritePlace.cleanName], after the others, as [TripPlace.ownName] calls it */
    suspend fun add(name: String, place: TripPlace): FavoritePlace

    suspend fun rename(id: Long, name: String)

    suspend fun delete(id: Long)

    /**
     * Put [place] back as it was before it was deleted, as Undo does: its
     * name, where it is, its stop and its place in the order, under its
     * own id unless another place took it meanwhile
     */
    suspend fun restore(place: FavoritePlace): FavoritePlace
}
