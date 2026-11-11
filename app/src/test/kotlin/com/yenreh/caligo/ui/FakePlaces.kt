package com.yenreh.caligo.ui

import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.repositories.PlacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Saved places in memory, [stored] until first loaded, names cleaned and
 * refused as PlacesRepositoryImpl does them; [failure] thrown by every
 * change, when set. [names] records each name as the caller gave it.
 */
class FakePlacesRepository(private val stored: List<FavoritePlace> = emptyList()) : PlacesRepository {
    private val saved = MutableStateFlow<List<FavoritePlace>>(emptyList())
    override val places: StateFlow<List<FavoritePlace>> = saved
    private var all = stored
    var failure: Throwable? = null
    val names = mutableListOf<String>()

    override suspend fun load() {
        saved.value = all
    }

    override suspend fun all(): List<FavoritePlace> = all

    override suspend fun add(name: String, place: TripPlace): FavoritePlace {
        failure?.let { throw it }
        val added = FavoritePlace(
            id = (all.maxOfOrNull { it.id } ?: 0) + 1,
            name = cleaned(name),
            kind = place.kind,
            label = place.ownName,
            detail = place.detail,
            latitude = place.latitude,
            longitude = place.longitude,
            stopId = place.stopId,
            position = (all.maxOfOrNull { it.position } ?: -1) + 1,
        )
        all = all + added
        load()
        return added
    }

    override suspend fun rename(id: Long, name: String) {
        failure?.let { throw it }
        val clean = cleaned(name)
        all = all.map { if (it.id == id) it.copy(name = clean) else it }
        load()
    }

    override suspend fun delete(id: Long) {
        failure?.let { throw it }
        all = all.filter { it.id != id }
        load()
    }

    /** As storage does: at its position, the rest from there on one further, under its id while free */
    override suspend fun restore(place: FavoritePlace): FavoritePlace {
        failure?.let { throw it }
        val shifted = if (all.any { it.position == place.position }) {
            all.map { if (it.position >= place.position) it.copy(position = it.position + 1) else it }
        } else {
            all
        }
        val restored = if (all.any { it.id == place.id }) place.copy(id = all.maxOf { it.id } + 1) else place
        all = (shifted + restored).sortedWith(compareBy({ it.position }, { it.id }))
        load()
        return restored
    }

    private fun cleaned(name: String): String {
        names.add(name)
        return requireNotNull(FavoritePlace.cleanName(name)) { "A place needs a name" }
    }
}
