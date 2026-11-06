package com.yenreh.caligo.data.datasources

/** Base exception for API errors */
open class ApiException(
    message: String,
    val statusCode: Int? = null,
    /** Whether retrying the request may succeed */
    val isRetryable: Boolean = false,
) : Exception(message) {
    override val message: String get() = super.message!!

    override fun toString() = "ApiException: $message"
}

/** Network failure or timeout. Transient, retryable. */
class NetworkApiException(message: String) : ApiException(message, isRetryable = true)

/** Server-side failure (5xx, 429, malformed payloads). Transient by default. */
class ServerApiException(
    message: String,
    statusCode: Int? = null,
    isRetryable: Boolean = true,
) : ApiException(message, statusCode, isRetryable)

/** The card number is invalid (not 13 digits). Not retryable. */
class InvalidCardApiException(message: String) : ApiException(message)

/** The card exists but has no movements/balance info. Not retryable. */
class CardNotFoundApiException(message: String) : ApiException(message)

/**
 * The service rate-limited this origin. Not retryable: immediate retries
 * only extend the penalty window.
 */
class RateLimitApiException(message: String) : ApiException(message)
