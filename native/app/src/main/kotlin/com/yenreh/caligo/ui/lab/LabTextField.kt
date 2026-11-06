package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * One line of input as the Flutter theme decorates it: paper inside a
 * rule, 2dp of accent while focused, 14 of padding. [hint] shows while
 * empty; [prefix], like Flutter's prefixText, only once there is text or
 * focus. [style] is the text's, which the hint and prefix take, muted.
 */
@Composable
fun LabTextField(
    state: TextFieldState,
    style: TextStyle,
    modifier: Modifier = Modifier,
    hint: String? = null,
    prefix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val p = LabTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val empty = state.text.isEmpty()
    BasicTextField(
        state = state,
        modifier = modifier,
        textStyle = style,
        keyboardOptions = keyboardOptions,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        cursorBrush = SolidColor(p.accent),
        decorator = { input ->
            Row(
                Modifier
                    .background(p.paper)
                    .border(if (focused) 2.dp else 1.dp, if (focused) p.accent else p.rule)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (prefix != null) {
                    Text(
                        text = prefix,
                        modifier = Modifier.alpha(if (empty && !focused) 0f else 1f),
                        style = style.copy(color = p.muted),
                    )
                }
                Box(Modifier.weight(1f)) {
                    if (empty && hint != null) Text(hint, style = style.copy(color = p.muted), maxLines = 1)
                    input()
                }
            }
        },
    )
}
