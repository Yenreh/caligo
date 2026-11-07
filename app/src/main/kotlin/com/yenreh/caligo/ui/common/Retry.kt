package com.yenreh.caligo.ui.common

import com.yenreh.caligo.data.datasources.ApiException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How long to wait before the next automatic try, after [retries] of them; null for no more */
typealias RetryRule = (retries: Int, error: Throwable) -> Duration?

/** Riverpod 3.0.3's retry of a provider that fails by itself */
object ProviderRetry {
    const val MAX_RETRIES = 10
    val MIN_DELAY = 200.milliseconds
    val MAX_DELAY = 6400.milliseconds

    /**
     * ProviderContainer.defaultRetry: ten times, 200 ms after the first
     * failure and twice as long after each next one, 6.4 s at most. Dart
     * Errors are bugs and are not retried; here only the service's
     * failures stand for Dart Exceptions. A location that cannot be read
     * is told at once: the Dart LocationUnavailableException and the
     * position's timeout were retried too, which spun the nearby list up
     * to 38 s with location off, and minutes indoors.
     */
    val default: RetryRule = { retries, error ->
        when {
            retries >= MAX_RETRIES || error !is ApiException -> null
            else -> (MIN_DELAY * (1 shl retries)).coerceAtMost(MAX_DELAY)
        }
    }

    /** No automatic tries, for tests that look at a failure itself */
    val none: RetryRule = { _, _ -> null }
}

/**
 * The automatic retries of one provider, as Riverpod 3 counts them: over
 * the provider's life, never reset, so ten in all. A [cancel] in between,
 * which the provider rebuilt by hand (its invalidate, a screen's Retry)
 * or disposed does, drops the one pending and spends none.
 *
 * [lock] guards the count and the pending try, and is held while
 * [afterFailure]'s retry runs; a caller with its own lock passes it.
 */
class AutoRetry(
    private val scope: CoroutineScope,
    private val rule: RetryRule = ProviderRetry.default,
    private val lock: Any = Any(),
) {
    private var retries = 0
    private var pending: Job? = null

    /**
     * After [error], whether it is tried again: then [again] runs once the
     * wait is over, unless [cancel] or another failure comes first
     */
    fun afterFailure(error: Throwable, again: () -> Unit): Boolean = synchronized(lock) {
        pending?.cancel()
        pending = null
        val wait = rule(retries, error) ?: return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            synchronized(lock) {
                // Cancelled while it waited for the lock
                if (pending !== coroutineContext[Job]) return@launch
                pending = null
                retries++
                again()
            }
        }
        pending = job
        job.start()
        true
    }

    /** Drop the try pending, if any */
    fun cancel() = synchronized(lock) {
        pending?.cancel()
        pending = null
    }
}

/**
 * A value loaded as a Riverpod FutureProvider and read through `when`:
 * loading until the first answer; a failure tried again by itself
 * ([AutoRetry]) and shown as loading meanwhile, as a provider being
 * retried is, then as a failure once the retries are spent. [reload],
 * the provider invalidated, keeps the last answer on show until the new
 * one comes.
 *
 * Not thread safe: for the main thread.
 */
class RetryingLoad<T>(
    private val scope: CoroutineScope,
    rule: RetryRule = ProviderRetry.default,
    private val load: suspend () -> T,
) {
    private val retry = AutoRetry(scope, rule)
    private val flow = MutableStateFlow<Loaded<T>>(Loaded.Loading)
    val state: StateFlow<Loaded<T>> = flow.asStateFlow()

    private var job: Job? = null

    /** Load unless it already has: watching the provider */
    fun watch() {
        if (job == null) reload()
    }

    /** Ask again, the last answer on show meanwhile: invalidating the provider */
    fun reload() {
        retry.cancel()
        start()
    }

    /** Stop loading and retrying: the provider disposed */
    fun cancel() {
        retry.cancel()
        job?.cancel()
    }

    private fun start() {
        job?.cancel()
        job = scope.launch {
            val answer = loadCatching(load)
            flow.value = if (answer is Loaded.Failed && retry.afterFailure(answer.error, ::start)) {
                Loaded.Loading
            } else {
                answer
            }
        }
    }
}
