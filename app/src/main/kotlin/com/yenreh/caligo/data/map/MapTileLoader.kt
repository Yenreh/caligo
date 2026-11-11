package com.yenreh.caligo.data.map

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** How asking for a tile went, for the maps to tell a tile server refusing them */
enum class TileOutcome {
    /** Fresh on disk: nothing asked */
    KEPT,

    /** The server answered with the tile, or that it had not changed */
    ANSWERED,

    /** The server would not serve it: 401 or 403, the key refused or out of quota, 429, or 5xx after the retries */
    REFUSED,

    /** No such tile, as 404 out of range, or another answer that says nothing of the server */
    MISSING,

    /** No answer at all: no connection, no name, a timeout; any other server would fare the same */
    UNREACHABLE,
}

/** A tile's image, null when there is none to draw, and how asking for it went */
class TileFetch(val bytes: ByteArray?, val outcome: TileOutcome)

/** Where tiles come from, for the maps: the loader, or a test's */
interface TileFetcher {
    /** The image at [url], sent with [headers], and how asking for it went */
    suspend fun fetch(url: String, headers: Map<String, String>): TileFetch
}

/**
 * The tiles of every map, through one client open for the whole run and
 * the disk cache, as flutter_map's NetworkTileProvider over the Flutter
 * app's shared RetryClient fetched them:
 *
 * - a kept tile still fresh answers without asking;
 * - an old one is asked about with what the server said of it, and a Not
 *   Modified keeps it, with the new headers;
 * - a new one is kept with when to ask again, from its Cache-Control,
 *   Expires, Age and Date, or in a week when they do not tell;
 * - a 503 is asked again up to three times, after 0.5, 0.75 and 1.125 s;
 * - an old tile is drawn when asking fails, by the connection or the
 *   server: flutter_map drew none, which left gaps on a spotty connection;
 * - with no tile kept, an error answer with a body is drawn as it is, not
 *   kept, since some servers explain an error in an image; anything else
 *   is no tile.
 */
class MapTileLoader(
    client: OkHttpClient,
    private val cache: MapTileCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelay: (attempt: Int) -> Duration = { 500.milliseconds * 1.5.pow(it) },
) : TileFetcher {
    /**
     * Tiles come many at once from one host, which the client's default of
     * five calls per host would queue
     */
    private val client = client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = CONCURRENT_TILES })
        .retryOnConnectionFailure(false)
        .build()

    /**
     * The image at [url], sent with [headers]; null when there is none to
     * draw. Kept on disk under [url] without its API key.
     */
    suspend fun load(url: String, headers: Map<String, String>): ByteArray? = fetch(url, headers).bytes

    override suspend fun fetch(url: String, headers: Map<String, String>): TileFetch {
        val cached = cache.get(url)
        if (cached != null && now() <= cached.metadata.staleAt) return TileFetch(cached.bytes, TileOutcome.KEPT)

        return try {
            val conditional = Headers.Builder().apply {
                cached?.metadata?.lastModified?.let { add("If-Modified-Since", java.util.Date(it)) }
                cached?.metadata?.etag?.let { add("If-None-Match", it) }
            }.build()
            val answer = get(url, headers, conditional)
            val outcome = outcomeOf(answer.code)
            val bytes = when {
                cached != null && answer.code == 304 -> {
                    keep(url, answer.headers, bytes = null)
                    cached.bytes
                }
                answer.code == 200 -> {
                    keep(url, answer.headers, answer.body)
                    answer.body
                }
                // The old tile is still the map
                cached != null -> cached.bytes
                // An error explained in an image, perhaps: drawn, never kept
                answer.body.isNotEmpty() -> answer.body
                else -> null
            }
            TileFetch(bytes, outcome)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No connection, or the call cut short: the old tile if any
            TileFetch(cached?.bytes, TileOutcome.UNREACHABLE)
        }
    }

    private fun keep(url: String, headers: Headers, bytes: ByteArray?) {
        val metadata = metadataFrom(headers, now()) ?: return
        cache.put(url, metadata, bytes)
    }

    private class Answer(val code: Int, val headers: Headers, val body: ByteArray)

    /** GET [url], asking again after a 503 as the Flutter app's RetryClient did */
    private suspend fun get(url: String, headers: Map<String, String>, conditional: Headers): Answer {
        var attempt = 0
        while (true) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
                conditional.forEach { (name, value) -> header(name, value) }
            }.build()
            val answer = client.newCall(request).await()
            if (answer.code != 503 || attempt == RETRIES) return answer
            delay(retryDelay(attempt))
            attempt++
        }
    }

    private suspend fun Call.await(): Answer = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val answer = try {
                        response.use { Answer(it.code, it.headers, it.body.bytes()) }
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

    companion object {
        /** Tiles asked for at once, from each tile server */
        const val CONCURRENT_TILES = 8

        private const val RETRIES = 3

        /** When the server does not say how long a tile keeps, as flutter_map's fallback */
        private val FALLBACK_FRESHNESS = 7.days

        private val MAX_AGE = Regex("max-age=(\\d+)")

        /** What an answer's [code] says of the server */
        fun outcomeOf(code: Int): TileOutcome = when (code) {
            200, 304 -> TileOutcome.ANSWERED
            401, 403, 429 -> TileOutcome.REFUSED
            in 500..599 -> TileOutcome.REFUSED
            else -> TileOutcome.MISSING
        }

        /**
         * When a tile answered with [headers] at [now] goes stale, and what
         * to ask about it with then, as flutter_map's
         * CachedMapTileMetadata.fromHttpHeaders, a max-age without a Date
         * counted from now; null when a date sent is unreadable, and then the
         * tile is not kept.
         */
        fun metadataFrom(headers: Headers, now: Long): TileMetadata? {
            fun date(name: String): Long? = headers.getDate(name)?.time

            val staleAt: Long = run {
                val cacheControl = headers["Cache-Control"]?.lowercase()
                    ?: return@run now + FALLBACK_FRESHNESS.inWholeMilliseconds
                val maxAge = MAX_AGE.find(cacheControl)?.groupValues?.get(1)?.toLongOrNull()
                if (maxAge == null) {
                    if (headers["Expires"] != null) return@run date("Expires") ?: return null
                    return@run now + FALLBACK_FRESHNESS.inWholeMilliseconds
                }
                val age = headers["Age"]
                if (age != null) {
                    val current = age.trim().toLongOrNull() ?: return null
                    return@run now + (maxAge - current) * 1000
                }
                // No Date: the answer is taken as just sent, where
                // flutter_map kept nothing
                val sent = if (headers["Date"] != null) date("Date") ?: return null else now
                val estimatedAge = max(0, (now - sent) / 1000)
                now + (maxAge - estimatedAge) * 1000
            }
            val lastModified = if (headers["Last-Modified"] != null) date("Last-Modified") ?: return null else null
            return TileMetadata(staleAt, lastModified, headers["ETag"])
        }
    }
}
