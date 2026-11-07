package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.FavoriteStop
import com.yenreh.caligo.domain.entities.StopLine
import com.yenreh.caligo.ui.common.rememberLoader
import com.yenreh.caligo.ui.lab.LabBottomSheet
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabOutlinedButton
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme

/** What the user chose for a favorite */
data class FavoriteEdit(val name: String, val lines: List<String>)

/**
 * Edit a favorite's own label and the lines it shows. [onDismiss] when
 * dismissed, [onSave] with the choice otherwise.
 *
 * [seenLines] are the lines of the buses already shown for it: an area
 * favorite has no line list of its own, and a stop's list may lag behind
 * what actually passes. The stop's own list comes from [loadLines].
 */
@Composable
fun EditFavoriteSheet(
    stop: FavoriteStop,
    seenLines: List<String>,
    loadLines: suspend (stopId: String) -> List<StopLine>,
    onDismiss: () -> Unit,
    onSave: (FavoriteEdit) -> Unit,
) {
    LabBottomSheet(onDismiss = onDismiss, dragHandle = true) {
        val stopId = stop.stopId
        val listed = if (stopId == null) {
            emptyList()
        } else {
            rememberLoader(stopId) { loadLines(stopId) }.state.valueOrNull.orEmpty()
        }
        EditFavoriteContent(stop, seenLines, listed, onDismiss, onSave)
    }
}

/** The sheet's content, apart so it shows without one */
@Composable
fun EditFavoriteContent(
    stop: FavoriteStop,
    seenLines: List<String>,
    listedLines: List<StopLine>,
    onDismiss: () -> Unit,
    onSave: (FavoriteEdit) -> Unit,
    name: TextFieldState = rememberTextFieldState(stop.customName.orEmpty()),
) {
    val p = LabTheme.palette
    val chosen = rememberSaveable(saver = chosenSaver) { stop.lines.toMutableStateList() }

    // Every line worth offering, including any chosen that no longer shows
    // up, so it can still be taken off
    val lines = (listedLines.map { it.shortName } + seenLines + chosen).toSet().sorted()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
            .testTag("edit_favorite"),
    ) {
        Text(stringResource(R.string.edit_stop), style = MaterialTheme.typography.titleLarge.copy(color = p.ink))
        Spacer(Modifier.height(20.dp))
        LabSectionLabel(stringResource(R.string.custom_name_label))
        Spacer(Modifier.height(8.dp))
        LabTextField(
            state = name,
            style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_name"),
            hint = stop.name,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.custom_name_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        LabSectionLabel(stringResource(R.string.lines_to_show))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.lines_to_show_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.no_lines_for_stop),
                style = MaterialTheme.typography.bodyMedium.copy(color = p.muted),
            )
        } else {
            LineToggles(lines, chosen)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            LabOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            LabFilledButton(
                onClick = { onSave(FavoriteEdit(name.text.toString(), chosen.sorted())) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun LineToggles(lines: List<String>, chosen: MutableList<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in lines) {
            LineToggle(line, chosen = line in chosen) { if (!chosen.remove(line)) chosen.add(line) }
        }
    }
}

/** Line chip that can be chosen: outlined like the others, filled with accent once chosen */
@Composable
private fun LineToggle(label: String, chosen: Boolean, onClick: () -> Unit) {
    val p = LabTheme.palette
    Box(
        modifier = Modifier
            .semantics { selected = chosen }
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 56.dp, minHeight = 36.dp)
            .then(if (chosen) Modifier.background(p.accent) else Modifier)
            .border(1.dp, p.accent)
            .padding(horizontal = 10.dp)
            .testTag("line_toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LabText.mono(p, size = 14f, color = if (chosen) p.bg else p.accent))
    }
}

/** The chosen lines, kept through a rotation */
private val chosenSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)
