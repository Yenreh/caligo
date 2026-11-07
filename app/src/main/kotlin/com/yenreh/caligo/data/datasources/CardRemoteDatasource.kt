package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.json.JsonFormatException
import com.yenreh.caligo.data.json.dartString
import com.yenreh.caligo.data.json.double
import com.yenreh.caligo.data.json.isNum
import com.yenreh.caligo.data.json.long
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.domain.entities.CardBalance
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Balance of a card, from Metrocali's balance service. It queries the
 * fare system server-side, so its quota is not tied to the user's IP.
 */
class CardRemoteDatasource(
    client: OkHttpClient,
    private val random: Random = Random.Default,
    private val backoffBase: Duration = BASE_BACKOFF,
    /** Left alone for a while after it pushes back */
    private val guard: ServiceGuard = ServiceGuard(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val client = client.forService(REQUEST_TIMEOUT)

    /** The balance of [cardId], the full 13-digit card number */
    suspend fun getCardBalance(cardId: String): CardBalance {
        // The proxy answers a malformed number with the same generic error
        // as any failure, so catch it here, without a request
        if (!cardNumber.matches(cardId)) {
            throw InvalidCardApiException("Card number must have exactly 13 digits")
        }

        guard.check()
        try {
            val response = withRetries { fetch(cardId) }
            guard.answered()
            return response
        } catch (e: ApiException) {
            if (ServiceGuard.isPushBack(e)) guard.pushedBack()
            throw e
        }
    }

    /** Run [request], retrying transient failures with exponential backoff */
    private suspend fun withRetries(request: suspend () -> CardBalance): CardBalance {
        var lastError: ApiException? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(backoffFor(attempt))
            try {
                return request()
            } catch (e: ApiException) {
                if (!e.isRetryable) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    private fun backoffFor(attempt: Int): Duration {
        val multiplier = 1 shl (attempt - 1)
        val jitter = random.nextInt(MAX_JITTER_MS)
        return (backoffBase.inWholeMilliseconds * multiplier + jitter).milliseconds
    }

    /**
     * Success: {"cardNumber": 1906051868981, "balance": 15300.0,
     *           "balanceDate": 1785631011000, ...}
     * Failure: {"error": "..."} with HTTP 200.
     */
    private suspend fun fetch(cardId: String): CardBalance {
        val response = client.getText("$CTS_URL?numero=$cardId", HEADERS)
        response.checkStatus()
        val payload = decodeJsonObject(response.body)

        val error = payload["error"]
        if (error != null && error !is JsonNull) {
            // The proxy collapses every upstream problem into one generic
            // message; asking again at once would only get the same one
            throw ServerApiException("Proxy error: ${error.dartString()}", isRetryable = false)
        }
        return toBalance(payload)
    }

    private fun decodeJsonObject(body: String): JsonObject =
        try {
            parseJson(body) as? JsonObject
        } catch (_: JsonFormatException) {
            null
        }
            // HTML error page, empty body or any non-JSON payload: the
            // service is misbehaving, retrying may help
            ?: throw ServerApiException("Unexpected response format")

    /** A card the fare system has no movements for comes back with a zero card number */
    private fun toBalance(payload: JsonObject): CardBalance {
        val number = payload["cardNumber"]
        if (!payload["balance"].isNum || !number.isNum || number.double() <= 0) {
            throw CardNotFoundApiException("Card not found")
        }
        val date = payload["balanceDate"]
        return CardBalance(
            balance = payload["balance"].double(),
            cardNumber = number.dartString()!!,
            // Epoch milliseconds from the Metrocali proxy
            balanceDate = if (date.isNum) date.long() else now(),
        )
    }

    companion object {
        const val CTS_URL = "https://metrocali.gov.co/cts/api/cts.php"
        private val HEADERS = mapOf("Accept" to "application/json")

        /** A transit card number, prefix and suffix included */
        private val cardNumber = Regex("^\\d{13}$")

        /**
         * A second attempt covers a dropped connection; more only press a
         * service that is struggling
         */
        private const val MAX_ATTEMPTS = 2
        val REQUEST_TIMEOUT = 10.seconds
        private val BASE_BACKOFF = 1.seconds
        private const val MAX_JITTER_MS = 500
    }
}
