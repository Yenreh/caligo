package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.dropped
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.data.assertThrowsSuspend
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** test/card_remote_datasource_test.dart */
class CardRemoteDatasourceTest {
    /** Random without jitter so retries are instant in tests */
    private object NoJitter : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextInt(until: Int) = 0
    }

    private var service: MockService? = null

    @After
    fun close() {
        service?.close()
    }

    /** Serves [respond] for each call to the proxy and counts them */
    private fun proxy(respond: (call: Int, url: HttpUrl) -> MockResponse) =
        MockService(respond).also { service = it }

    private fun MockService.datasource() =
        CardRemoteDatasource(client, random = NoJitter, backoffBase = Duration.ZERO)

    private fun MockService.assertAskedForTheCard() {
        for ((url, headers) in requested.zip(this.headers)) {
            assertEquals("https://metrocali.gov.co/cts/api/cts.php?numero=$CARD", url.toString())
            assertEquals("application/json", headers["Accept"])
        }
    }

    @Test
    fun parsesTheBalanceAndItsEpochDate() = runTest {
        val proxy = proxy { _, _ -> ok(SUCCESS_BODY) }

        val result = proxy.datasource().getCardBalance(CARD)

        assertEquals(1, proxy.calls)
        proxy.assertAskedForTheCard()
        assertEquals(15300.0, result.balance, 0.0)
        assertEquals("1906051868981", result.cardNumber)
        assertEquals(1785631011000, result.balanceDate)
    }

    @Test
    fun rejectsAMalformedCardNumberWithoutAsking() = runTest {
        val proxy = proxy { _, _ -> ok(SUCCESS_BODY) }

        assertThrowsSuspend<InvalidCardApiException> { proxy.datasource().getCardBalance("19060518") }
        assertEquals(0, proxy.calls)
    }

    @Test
    fun reportsACardWithoutMovements() = runTest {
        val proxy = proxy { _, _ -> ok("""{"cardNumber": 0, "balance": 0, "balanceDate": null}""") }

        assertThrowsSuspend<CardNotFoundApiException> { proxy.datasource().getCardBalance(CARD) }
    }

    @Test
    fun doesNotRetryTheGenericProxyError() = runTest {
        val proxy = proxy { _, _ -> ok(PROXY_ERROR_BODY) }

        val error = assertThrowsSuspend<ServerApiException> { proxy.datasource().getCardBalance(CARD) }
        assertEquals(1, proxy.calls)
        assertEquals("Proxy error: Error al consultar la API externa", error.message)
    }

    @Test
    fun retriesA5xxOnceBeforeSucceeding() = runTest {
        val proxy = proxy { call, _ ->
            if (call < 2) status(500, "Internal Server Error") else ok(SUCCESS_BODY)
        }

        val result = proxy.datasource().getCardBalance(CARD)

        assertEquals(2, proxy.calls)
        proxy.assertAskedForTheCard()
        assertEquals(15300.0, result.balance, 0.0)
    }

    @Test
    fun retriesADroppedConnectionOnceBeforeGivingUp() = runTest {
        val proxy = proxy { _, _ -> dropped() }

        assertThrowsSuspend<NetworkApiException> { proxy.datasource().getCardBalance(CARD) }
        assertEquals(2, proxy.calls)
    }

    @Test
    fun treatsNonJsonBodiesAsRetryableServerErrors() = runTest {
        val proxy = proxy { _, _ -> ok("<html>Service Unavailable</html>") }

        assertThrowsSuspend<ServerApiException> { proxy.datasource().getCardBalance(CARD) }
        assertEquals(2, proxy.calls)
    }

    @Test
    fun doesNotRetryNon5xxClientErrors() = runTest {
        val proxy = proxy { _, _ -> status(404, "Not Found") }

        assertThrowsSuspend<ServerApiException> { proxy.datasource().getCardBalance(CARD) }
        assertEquals(1, proxy.calls)
    }

    /** CardRemoteDatasource when the proxy pushes back */
    @Test
    fun doesNotRetryARateLimitAndRestsBeforeAskingAgain() = runTest {
        val proxy = proxy { _, _ -> status(429, "Too Many") }
        val datasource = proxy.datasource()

        assertThrowsSuspend<ServerApiException> { datasource.getCardBalance(CARD) }
        val resting = assertThrowsSuspend<RateLimitApiException> { datasource.getCardBalance(CARD) }
        assertSame(ServiceGuard.RESTING, resting)
        assertEquals(1, proxy.calls)
    }

    private companion object {
        const val CARD = "1906051868981"

        /** Metrocali proxy success payload */
        const val SUCCESS_BODY = """{"cardNumber":1906051868981,"cd_id":6,""" +
            """"crd_snr":5186898,"tsn":525,"balance":15300.0,""" +
            """"balanceDate":1785631011000}"""

        const val PROXY_ERROR_BODY = """{"error":"Error al consultar la API externa"}"""
    }
}
