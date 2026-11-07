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
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every MIO line and the hours each runs: the Flutter app's linesProvider
 * and lineHoursProvider. Each loads on first use. The lines keep their
 * answer for the app's life, even a failure, until [reloadLines]; the
 * hours keep theirs for a day, and a failure until next asked, where the
 * Flutter provider kept either for the run.
 *
 * One per activity, shared by the screens that show lines.
 */
class LinesViewModel(
    private val repository: StopsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
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

    /** When the hours last answered; null until they do, and after a failure */
    private var hoursAt: Long? = null

    /**
     * Hours each line runs, by name; empty until they come. Missing hours
     * only hide a status, so a failure leaves them empty rather than an
     * error.
     */
    val hours: StateFlow<Map<String, LineHours>> = hoursState.asStateFlow()

    private val answeredHours = MutableStateFlow<Map<String, LineHours>?>(null)

    /**
     * [hours], null until they come: the trip planner plans again once
     * they first do, as the Flutter one does on the provider's first value
     */
    val loadedHours: StateFlow<Map<String, LineHours>?> = answeredHours.asStateFlow()

    /**
     * Load the hours unless they are on their way or answered within a
     * day, as long as the cache keeps them
     */
    fun watchHours() {
        if (hoursJob?.isActive == true) return
        val at = hoursAt
        if (at != null && now() - at < HOURS_MAX_AGE.inWholeMilliseconds) return
        hoursJob = viewModelScope.launch {
            val answer = try {
                repository.getLineHours().also { hoursAt = now() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Any failure: the Flutter provider turns an ApiException
                // into no hours, and its screens read any other as none too.
                // Hours already had stay.
                hoursAt = null
                hoursState.value
            }
            hoursState.value = answer
            answeredHours.value = answer
        }
    }

    companion object {
        /** The hours change a few times a year: as long as the cache keeps them */
        private val HOURS_MAX_AGE = 1.days

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CaliGoApp).container
                LinesViewModel(container.stopsRepository)
            }
        }
    }
}
