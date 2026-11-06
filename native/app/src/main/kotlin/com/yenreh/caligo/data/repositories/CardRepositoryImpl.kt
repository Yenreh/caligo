package com.yenreh.caligo.data.repositories

import com.yenreh.caligo.data.datasources.CardLocalDatasource
import com.yenreh.caligo.data.datasources.CardRemoteDatasource
import com.yenreh.caligo.domain.entities.CardBalance
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.repositories.CardRepository

class CardRepositoryImpl(
    private val local: CardLocalDatasource,
    private val remote: CardRemoteDatasource,
) : CardRepository {
    override suspend fun getAllCards() = local.getAllCards()

    override suspend fun getCardById(id: String) = local.getCardById(id)

    override suspend fun createCard(card: CardEntity) = local.insertCard(card)

    override suspend fun updateCard(card: CardEntity) = local.updateCard(card)

    override suspend fun deleteCard(id: String) = local.deleteCard(id)

    /** The service takes the full number: prefix, id and suffix */
    override suspend fun refreshCardBalance(card: CardEntity): CardBalance =
        remote.getCardBalance("${card.prefix}${card.id}${card.suffix}")

    override suspend fun updateCardBalance(id: String, balance: Double, lastUpdate: Long) =
        local.updateCardBalance(id, balance, lastUpdate)
}
