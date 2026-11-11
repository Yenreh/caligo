package com.yenreh.caligo.ui.trips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.lab.LabBottomSheet
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabOutlinedButton
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.theme.LabTheme

/** Name [place] to save it for quick plans: the box starts with its own name, all selected */
@Composable
fun SavePlaceSheet(place: TripPlace, onDismiss: () -> Unit, onSave: (name: String) -> Unit) {
    LabBottomSheet(onDismiss = onDismiss, dragHandle = true) {
        PlaceNameContent(
            title = stringResource(R.string.save_place),
            about = listOfNotNull(place.name, place.detail).joinToString(" · "),
            initial = FavoritePlace.suggestedName(place.name),
            onDismiss = onDismiss,
            onSave = onSave,
        )
    }
}

/** Rename or delete a saved [place] */
@Composable
fun EditPlaceSheet(place: FavoritePlace, onDismiss: () -> Unit, onSave: (name: String) -> Unit, onDelete: () -> Unit) {
    LabBottomSheet(onDismiss = onDismiss, dragHandle = true) {
        PlaceNameContent(
            title = stringResource(R.string.edit_place),
            about = listOfNotNull(place.label, place.detail).joinToString(" · "),
            initial = place.name,
            onDismiss = onDismiss,
            onSave = onSave,
            onDelete = onDelete,
        )
    }
}

/**
 * A place's name, short as a chip needs it: [about] says which place,
 * Save takes the name once it holds a letter, the keyboard's Done too.
 * With [onDelete], a way to delete it below.
 */
@Composable
private fun PlaceNameContent(
    title: String,
    about: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    name: TextFieldState = rememberTextFieldState(initial, TextRange(0, initial.length)),
) {
    val p = LabTheme.palette
    val focus = remember { FocusRequester() }
    val clean = FavoritePlace.cleanName(name.text.toString())
    val save = { clean?.let(onSave) }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
            .semantics { paneTitle = title }
            .testTag("place_sheet"),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(color = p.ink))
        Spacer(Modifier.height(4.dp))
        Text(
            about,
            style = MaterialTheme.typography.bodyMedium.copy(color = p.muted),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        LabTextField(
            state = name,
            style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag("place_name"),
            label = stringResource(R.string.place_name_label),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            inputTransformation = InputTransformation.maxLength(FavoritePlace.MAX_NAME),
            onKeyboardAction = { save() },
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.place_name_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            LabOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            LabFilledButton(
                onClick = { save() },
                enabled = clean != null,
                modifier = Modifier
                    .weight(1f)
                    .testTag("place_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        }
        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            LabTextButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("place_delete"),
            ) {
                Text(stringResource(R.string.delete_place), color = p.crit)
            }
        }
    }
    // As autofocus: the keyboard comes up with the sheet, the name selected to type over
    LaunchedEffect(focus) { focus.requestFocus() }
}
