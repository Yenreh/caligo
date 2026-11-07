package com.yenreh.caligo.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.entities.LineHours
import com.yenreh.caligo.domain.entities.TransitLine
import com.yenreh.caligo.domain.repositories.StopsRepository
import com.yenreh.caligo.ui.common.Loaded
import com.yenreh.caligo.ui.common.loadCatching
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every MIO line and the hours each runs: the Flutter app's linesProvider
 * and lineHoursProvider. Each loads on first use and keeps its answer for
 * the app's life, the lines even a failure until [reloadLines].
 *
 * One per activity, shared by the screens that show lines.
 */
class LinesViewModel(private val repository: StopsRepository) : ViewModel() {
    private val linesState = MutableStateFlow<Loaded<List<TransitLine>>>(Loaded.Loading)
    private var linesJob: Job? = null

    /** Every MIO line, by name */
    val lines: StateFlow<Loaded<List<TransitLine>>> = linesState.asStateFlow()

    /** Load the lines unless they already are */
    fun watchLines() {
        if (linesJob == null) reloadLines()
    }

    /** Ask for the lines again, the last answer on show meanwhile */
    fun reloadLines() {
        linesJob?.cancel()
        linesJob = viewModelScope.launch { linesState.value = loadCatching { repository.getLines() } }
    }

    private val hoursState = MutableStateFlow<Map<String, LineHours>>(emptyMap())
    private var hoursJob: Job? = null

    /**
     * Hours each line runs, by name; empty until they come. Missing hours
     * only hide a status, so a failure leaves them empty rather than an
     * error.
     */
    val hours: StateFlow<Map<String, LineHours>> = hoursState.asStateFlow()

    /** Load the hours unless they already are */
    fun watchHours() {
        if (hoursJob != null) return
        hoursJob = viewModelScope.launch {
            hoursState.value = try {
                repository.getLineHours()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Any failure: the Flutter provider turns an ApiException
                // into no hours, and its screens read any other as none too
                emptyMap()
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                LinesViewModel(container.stopsRepository)
            }
        }
    }
}
