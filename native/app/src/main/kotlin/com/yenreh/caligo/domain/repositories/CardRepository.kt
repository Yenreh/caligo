package com.yenreh.caligo.domain.repositories

import com.yenreh.caligo.domain.entities.CardBalance
import com.yenreh.caligo.domain.entities.CardEntity

/** Repository interface for card operations */
interface CardRepository {
    suspend fun getAllCards(): List<CardEntity>

    /** The card with the number [id], prefix and suffix apart */
    suspend fun getCardById(id: String): CardEntity?

    suspend fun createCard(card: CardEntity)

    suspend fun updateCard(card: CardEntity)

    suspend fun deleteCard(id: String)

    /** Ask the service for the balance of [card] */
    suspend fun refreshCardBalance(card: CardEntity): CardBalance

    /** Save a balance the service reported; [lastUpdate] in epoch milliseconds */
    suspend fun updateCardBalance(id: String, balance: Double, lastUpdate: Long)
}
