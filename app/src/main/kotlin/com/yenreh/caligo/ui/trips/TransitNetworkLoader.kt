package com.yenreh.caligo.ui.trips

import com.yenreh.caligo.domain.TransitNetwork
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.ui.common.AutoRetry
import com.yenreh.caligo.ui.common.ProviderRetry
import com.yenreh.caligo.ui.common.RetryRule
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * provider ([AutoRetry]): ten times at most over the run. A [reload] in
 * between, the screen's Retry, starts at once and spends none of them.
 */
class TransitNetworkLoader(
    private val repository: StopsRepository,
    /** Outlives every screen, as the provider does */
    private val scope: CoroutineScope,
    /** Where the network is put together: thousands of stops, off the main thread */
    private val building: CoroutineDispatcher = Dispatchers.Default,
    /** When a failed load is tried again by itself */
    retry: RetryRule = ProviderRetry.default,
) {
    private val state = MutableStateFlow(NetworkLoad())
    val load: StateFlow<NetworkLoad> = state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private val retries = AutoRetry(scope, retry, lock)

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
        retries.cancel()
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
                    retries.afterFailure(e, ::start)
                }
            }
        }
    }
}
