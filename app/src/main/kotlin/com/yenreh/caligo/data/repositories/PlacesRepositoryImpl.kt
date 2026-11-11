package com.yenreh.caligo.data.repositories

import com.yenreh.caligo.data.datasources.PlacesLocalDatasource
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.domain.repositories.PlacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The saved places, read again from storage after every change */
class PlacesRepositoryImpl(private val local: PlacesLocalDatasource) : PlacesRepository {
    private val saved = MutableStateFlow<List<FavoritePlace>>(emptyList())
    override val places: StateFlow<List<FavoritePlace>> = saved.asStateFlow()

    override suspend fun load() {
        saved.value = local.getAll()
    }

    override suspend fun add(name: String, place: TripPlace): FavoritePlace {
        val added = local.insert(
            FavoritePlace(
                id = 0,
                name = cleaned(name),
                kind = place.kind,
                label = place.ownName,
                detail = place.detail,
                latitude = place.latitude,
                longitude = place.longitude,
                stopId = place.stopId,
                position = 0,
            ),
        )
        load()
        return added
    }

    override suspend fun rename(id: Long, name: String) {
        local.rename(id, cleaned(name))
        load()
    }

    override suspend fun delete(id: Long) {
        local.delete(id)
        load()
    }

    private fun cleaned(name: String) = requireNotNull(FavoritePlace.cleanName(name)) { "A place needs a name" }
}
