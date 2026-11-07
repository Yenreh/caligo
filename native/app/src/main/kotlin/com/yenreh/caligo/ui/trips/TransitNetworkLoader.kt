package com.yenreh.caligo.ui.trips

import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.domain.TransitNetwork
import com.yenreh.caligo.domain.repositories.StopsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The route network as it loads: how many lines are in, then the network
 * itself, or why it could not load.
 *
 * Riverpod's AsyncValue of the Flutter NetworkLoad: a failure stays on
 * show while the load is tried again, until the first line of the new
 * try comes in.
 */
data class NetworkLoad(
    val done: Int = 0,
    val total: Int = 0,
    val network: TransitNetwork? = null,
    val error: Throwable? = null,
) {
    /** What the screen tells as a failure: an error and no network to plan on */
    val failed: Boolean get() = error != null && network == null
}

/**
 * Every route as one network to plan on: the Flutter app's
 * transitNetworkProvider. The first load asks for each line; after that
 * the routes come from the cache for a week. Kept for the whole run,
 * since it takes a moment to gather, and started by the first screen
 * that needs it.
 *
 * A load that fails is tried again on its own, as Riverpod 3 retries a
 * provider: ten times at most over the run, 200 ms after the first
 * failure and twice as long after each next one, 6.4 s at most. A
 * [reload] in between, the screen's Retry, starts at once and spends
 * none of them.
 */
class TransitNetworkLoader(
    private val repository: StopsRepository,
    /** Outlives every screen, as the provider does */
    private val scope: CoroutineScope,
    /** Where the network is put together: thousands of stops, off the main thread */
    private val building: CoroutineDispatcher = Dispatchers.Default,
    /** How long to wait before the next automatic try, after [retries] of them; null for no more */
    private val retryAfter: (retries: Int, error: Throwable) -> Duration? = ::riverpodRetry,
) {
    private val state = MutableStateFlow(NetworkLoad())
    val load: StateFlow<NetworkLoad> = state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var retry: Job? = null
    private var retries = 0

    /** Counts the loads started, so a superseded one reports nothing */
    private var generation = 0

    /** Start loading unless it already has: watching the provider */
    fun watch() = synchronized(lock) {
        if (job == null) start()
    }

    /** Load again from the start, the last state on show meanwhile: invalidating the provider */
    fun reload() = synchronized(lock) { start() }

    /** Under [lock] */
    private fun start() {
        retry?.cancel()
        retry = null
        job?.cancel()
        val load = ++generation
        fun current() = synchronized(lock) { load == generation }

        job = scope.launch {
            try {
                val routes = repository.getAllLineStops { done, total ->
                    if (current()) state.value = NetworkLoad(done, total)
                }
                val network = withContext(building) { TransitNetwork.fromRoutes(routes) }
                if (current()) state.value = NetworkLoad(routes.size, routes.size, network)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(lock) {
                    if (load != generation) return@launch
                    state.update { it.copy(error = e) }
                    scheduleRetry(e)
                }
            }
        }
    }

    /** Under [lock] */
    private fun scheduleRetry(error: Throwable) {
        val wait = retryAfter(retries, error) ?: return
        retry = scope.launch {
            delay(wait)
            synchronized(lock) {
                if (retry !== coroutineContext[Job]) return@launch
                retry = null
                retries++
                start()
            }
        }
    }

    companion object {
        /**
         * Riverpod 3's default retry: ten times, from 200 ms doubling to
         * 6.4 s. Dart Errors are bugs and are not retried; here only the
         * service's failures stand for Dart Exceptions.
         */
        fun riverpodRetry(retries: Int, error: Throwable): Duration? {
            if (retries >= MAX_RETRIES || error !is ApiException) return null
            val wait = MIN_RETRY_DELAY * (1 shl retries)
            return if (wait > MAX_RETRY_DELAY) MAX_RETRY_DELAY else wait
        }

        private const val MAX_RETRIES = 10
        private val MIN_RETRY_DELAY = 200.milliseconds
        private val MAX_RETRY_DELAY = 6400.milliseconds
    }
}
