package com.yenreh.caligo.data.datasources

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Leaves a public service alone for a while after it pushes back.
 *
 * Rate limits and server errors that outlast a retry are the service
 * asking for less, and polling on schedule regardless is how an origin
 * gets blocked. Each push back in a row doubles the wait, from
 * [FIRST_WAIT] up to [LONGEST_WAIT]; the first normal answer clears it.
 */
class ServiceGuard(private val now: () -> Long = System::currentTimeMillis) {
    private var strikes = 0
    private var until: Long? = null

    /** Throws [RESTING] while the service is being left alone */
    @Synchronized
    fun check() {
        val until = until
        if (until != null && now() < until) throw RESTING
    }

    @Synchronized
    fun pushedBack() {
        strikes++
        val wait = FIRST_WAIT * (1 shl min(strikes - 1, 30))
        until = now() + minOf(wait, LONGEST_WAIT).inWholeMilliseconds
    }

    @Synchronized
    fun answered() {
        strikes = 0
        until = null
    }

    companion object {
        val FIRST_WAIT: Duration = 1.minutes
        val LONGEST_WAIT: Duration = 16.minutes

        /**
         * One instance, so a refresh that keeps failing reports the same
         * error instead of looking like a new one each time
         */
        val RESTING = RateLimitApiException("Leaving the service alone for a while")

        /**
         * Whether [error] is the service pushing back, rather than the
         * device being offline or the request itself being wrong
         */
        fun isPushBack(error: ApiException): Boolean =
            error is RateLimitApiException ||
                error.statusCode == 429 ||
                (error.statusCode ?: 0) >= 500
    }
}
