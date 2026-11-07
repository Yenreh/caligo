package com.yenreh.caligo.data

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.Assert.fail

/**
 * A service on a MockWebServer, reached through a client that keeps the
 * real URLs: each request is recorded as the app sent it, then sent on
 * to the server. [respond] gets the request's number, from 1, and its URL.
 */
class MockService(private val respond: (call: Int, url: HttpUrl) -> MockResponse) : Closeable {
    private val served = AtomicInteger()

    /** Every URL asked for, as the app wrote it */
    val requested: MutableList<HttpUrl> = CopyOnWriteArrayList()
    val calls: Int get() = requested.size

    /** The headers of each request, as the app set them */
    val headers: MutableList<Headers> = CopyOnWriteArrayList()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                respond(served.incrementAndGet(), request.url)
        }
        start()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request().url
            requested.add(original)
            headers.add(chain.request().headers)
            val local = original.newBuilder()
                .scheme("http")
                .host(server.hostName)
                .port(server.port)
                .build()
            chain.proceed(chain.request().newBuilder().url(local).build())
        }
        .build()

    override fun close() = server.close()

    companion object {
        /** A JSON answer, as both services label theirs */
        fun ok(body: String) =
            MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

        fun status(code: Int, body: String = "") = MockResponse(code = code, body = body)

        /** The connection drops before any answer */
        fun dropped(): MockResponse =
            MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
    }
}

/** Run [block], expecting it to throw a [T]; returns what it threw */
suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
    }
    fail("Expected ${T::class.simpleName}, nothing was thrown")
    throw IllegalStateException()
}
