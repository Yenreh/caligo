package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Tone of a snackbar, shown as a small square marker */
enum class LabTone { NEUTRAL, OK, WARN, CRIT }

private class LabSnackbarVisuals(
    override val message: String,
    val tone: LabTone,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction = false

    // Timed by LabSnackbars, which Compose's three durations cannot
    override val duration = SnackbarDuration.Indefinite
}

/**
 * Lab snackbars on [host], one at a time: each replaces the one showing,
 * as the Flutter app clears the snackbars before showing the next.
 */
@Stable
class LabSnackbars(val host: SnackbarHostState, private val scope: CoroutineScope) {
    private var showing: Job? = null

    /**
     * Show [message] for [duration]. One with an action stays until it is
     * used or replaced, as a Flutter snackbar with an action persists.
     */
    fun show(
        message: String,
        tone: LabTone = LabTone.NEUTRAL,
        duration: Duration = 4.seconds,
        actionLabel: String? = null,
        onAction: () -> Unit = {},
    ) {
        showing?.cancel()
        showing = scope.launch {
            val visuals = LabSnackbarVisuals(message, tone, actionLabel)
            val result = if (actionLabel != null) {
                host.showSnackbar(visuals)
            } else {
                withTimeoutOrNull(duration) { host.showSnackbar(visuals) }
            }
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

@Composable
fun rememberLabSnackbars(): LabSnackbars {
    val scope = rememberCoroutineScope()
    return remember(scope) { LabSnackbars(SnackbarHostState(), scope) }
}

/** Where [snackbars] show, floating over the screen's bottom */
@Composable
fun LabSnackbarHost(snackbars: LabSnackbars, modifier: Modifier = Modifier) {
    SnackbarHost(snackbars.host, modifier) { LabSnackbar(it) }
}

/** Paper, rule and a coloured square for the tone */
@Composable
private fun LabSnackbar(data: SnackbarData) {
    val p = LabTheme.palette
    val visuals = data.visuals
    val color = when ((visuals as? LabSnackbarVisuals)?.tone ?: LabTone.NEUTRAL) {
        LabTone.NEUTRAL -> p.muted
        LabTone.OK -> p.ok
        LabTone.WARN -> p.warn
        LabTone.CRIT -> p.crit
    }
    val action = visuals.actionLabel
    Row(
        modifier = Modifier
            // Flutter's floating margin
            .padding(start = 15.dp, top = 5.dp, end = 15.dp, bottom = 10.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(p.paper2)
            .border(1.dp, p.rule)
            .padding(start = 17.dp, end = if (action != null) 9.dp else 17.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = visuals.message,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = p.ink),
        )
        if (action != null) {
            LabTextButton(onClick = data::performAction) { Text(action) }
        }
    }
}
