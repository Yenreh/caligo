package com.yenreh.caligo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * autoDispose provider: started when it first shows, cancelled when it
 * leaves, and run again by [reload], which Riverpod's invalidate does.
 */
@Stable
class Loader<T> internal constructor(private val scope: CoroutineScope, private val load: suspend () -> T) {
    var state: Loaded<T> by mutableStateOf(Loaded.Loading)
        private set

    private var job: Job? = null

    internal suspend fun first() {
        if (job != null || state != Loaded.Loading) return
        val answer = loadCatching(load)
        // A reload asked for meanwhile has the last word
        if (job == null) state = answer
    }

    /** Ask again, the last answer on show meanwhile */
    fun reload() {
        job?.cancel()
        job = scope.launch { state = loadCatching(load) }
    }
}

/** A [Loader] of [load], started at once and again whenever [key] changes */
@Composable
fun <T> rememberLoader(key: Any?, load: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(load)
    val loader = remember(key) { Loader(scope) { current() } }
    LaunchedEffect(loader) { loader.first() }
    return loader
}
