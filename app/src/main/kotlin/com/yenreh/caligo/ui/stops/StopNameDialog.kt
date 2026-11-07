package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yenreh.caligo.R
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * Ask for a label of the user's own for a stop, as the theme's
 * AlertDialog: paper inside a rule, square.
 *
 * [onDismiss] when dismissed; [onSave] with the text otherwise, which is
 * empty when no label was given: the stop keeps its real name.
 */
@Composable
fun StopNameDialog(
    title: String,
    realName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    initial: String = "",
) {
    val p = LabTheme.palette
    val name = rememberTextFieldState(initial)
    val focus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                // Flutter's dialog inset, and Material 3's width limits
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth()
                .background(p.paper)
                .border(1.dp, p.rule)
                .padding(24.dp)
                .semantics { paneTitle = title }
                .testTag("stop_name_dialog"),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(color = p.ink))
            Spacer(Modifier.height(16.dp))
            LabTextField(
                state = name,
                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("custom_name"),
                hint = realName,
                label = stringResource(R.string.custom_name_label),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.custom_name_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LabTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                LabFilledButton(onClick = { onSave(name.text.toString()) }) { Text(stringResource(R.string.save)) }
            }
        }
        // As autofocus: the keyboard comes up with the dialog
        LaunchedEffect(focus) { focus.requestFocus() }
    }
}
