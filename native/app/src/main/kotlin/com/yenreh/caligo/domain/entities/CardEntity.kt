package com.yenreh.caligo.domain.entities

/**
 * A transport card. Times are epoch milliseconds.
 *
 * Equality is structural, unlike the Dart entity's (by [internalId]): a
 * StateFlow compares lists with equals, and would drop a balance update.
 */
data class CardEntity(
    val internalId: String,
    val id: String,
    val prefix: String = "",
    val suffix: String = "",
    val name: String,
    val position: Int = 0,
    val balance: Double? = null,
    val lastUpdate: Long? = null,
) {
    /** The id with its prefix and suffix */
    val displayId: String
        get() = buildString {
            if (prefix.isNotEmpty()) append("$prefix ")
            append(id)
            if (suffix.isNotEmpty()) append(" $suffix")
        }
}

/** A balance as the service reports it */
data class CardBalance(
    val balance: Double,
    val cardNumber: String,
    /** Epoch milliseconds */
    val balanceDate: Long,
)
