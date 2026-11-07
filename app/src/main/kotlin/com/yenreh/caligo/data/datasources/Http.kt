package com.yenreh.caligo.data.datasources

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [this] client for one service: [timeout] for the whole request, as the
 * Flutter app's `.timeout`, and no attempts beyond the datasource's own,
 * which keep count of what each service is asked
 */
internal fun OkHttpClient.forService(timeout: Duration): OkHttpClient = newBuilder()
    .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(false)
    .build()

/** Status and body of an answer, the body decoded as Dart's http does */
internal class HttpAnswer(val statusCode: Int, val body: String)

/**
 * GET [url], as `http.Client.get` with a timeout did in the Flutter app:
 * no answer within the client's call timeout, or no connection, is a
 * [NetworkApiException].
 */
internal suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): HttpAnswer {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return try {
        newCall(request).await()
    } catch (_: InterruptedIOException) {
        throw NetworkApiException("Request timed out")
    } catch (e: IOException) {
        throw NetworkApiException("Connection error: ${e.message ?: e}")
    }
}

private suspend fun Call.await(): HttpAnswer = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val answer = try {
                    response.use { HttpAnswer(it.code, it.decodedBody()) }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(answer)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}

/**
 * The body in the charset the answer names; without one, UTF-8 for JSON
 * and Latin-1 for anything else, as package:http decides
 */
private fun Response.decodedBody(): String {
    val type = body.contentType()
    val charset = type?.charset()
        ?: if (type?.type == "application" && type.subtype == "json") Charsets.UTF_8 else Charsets.ISO_8859_1
    return String(body.bytes(), charset)
}

/**
 * The status checks both services share: a rate limit is never retried,
 * a server error may be, anything else but 200 is not.
 */
internal fun HttpAnswer.checkStatus() {
    when {
        statusCode == 429 -> throw ServerApiException("Rate limited", statusCode = 429, isRetryable = false)
        statusCode >= 500 -> throw ServerApiException("Server error: $statusCode", statusCode = statusCode)
        statusCode != 200 -> throw ServerApiException(
            "Unexpected status: $statusCode",
            statusCode = statusCode,
            isRetryable = false,
        )
    }
}

/**
 * [text] as Dart's `Uri.encodeComponent` writes it: UTF-8, every byte
 * percent-encoded but letters, digits and `-_.!~*'()`
 */
internal fun encodeUriComponent(text: String): String = buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val char = c.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UNRESERVED) {
            append(char)
        } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
}

private const val UNRESERVED = "-_.!~*'()"
private const val HEX = "0123456789ABCDEF"
