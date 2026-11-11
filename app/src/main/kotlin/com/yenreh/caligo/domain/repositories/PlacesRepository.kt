package com.yenreh.caligo.domain.repositories

import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import kotlinx.coroutines.flow.StateFlow

/** The places saved for quick plans, in the order saved */
interface PlacesRepository {
    /** What is saved, as last read or written; empty until [load] */
    val places: StateFlow<List<FavoritePlace>>

    /** Read them again from storage */
    suspend fun load()

    /** Save [place] under [name], cleaned as [FavoritePlace.cleanName], after the others, as [TripPlace.ownName] calls it */
    suspend fun add(name: String, place: TripPlace): FavoritePlace

    suspend fun rename(id: Long, name: String)

    suspend fun delete(id: Long)
}
