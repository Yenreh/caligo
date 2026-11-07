package com.yenreh.caligo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.cancellation.CancellationException

/**
 * A value loading, as a Riverpod AsyncValue shows one through `when`:
 * loading only until the first answer; a load asked for again keeps
 * that answer, value or error, on show until the new one comes.
 */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>

    data class Failed(val error: Throwable) : Loaded<Nothing>

    data class Ready<T>(val value: T) : Loaded<T>

    /** The value, when there is one */
    val valueOrNull: T? get() = (this as? Ready)?.value
}

/** What [load] answers, caught: an error is an answer too */
suspend fun <T> loadCatching(load: suspend () -> T): Loaded<T> = try {
    Loaded.Ready(load())
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Loaded.Failed(e)
}

/**
 * A load that lives as long as the screen part holding it, as a Riverpod
 * autoDispose provider: started when it first shows, retried by itself
 * as [RetryingLoad] is, cancelled when it leaves, and run again by
 * [reload], which Riverpod's invalidate does.
 */
@Stable
class Loader<T> internal constructor(private val load: RetryingLoad<T>) {
    /** What there is to show */
    val state: Loaded<T>
        @Composable get() = load.state.collectAsState().value

    /** Ask again, the last answer on show meanwhile */
    fun reload() = load.reload()
}

/** A [Loader] of [load], started at once and again whenever [key] changes */
@Composable
fun <T> rememberLoader(key: Any?, retry: RetryRule = ProviderRetry.default, load: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(load)
    val running = remember(key) { RetryingLoad(scope, retry) { current() } }
    DisposableEffect(running) {
        running.watch()
        onDispose { running.cancel() }
    }
    return remember(running) { Loader(running) }
}
