package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * One line of input as the Flutter theme decorates it: paper inside a
 * rule, 2dp of accent while focused, 14 of padding and Material 3's 4 of
 * gap beside the text. [hint] shows while empty; [prefix], like Flutter's
 * prefixText, only once there is text or focus; [leadingIcon], like its
 * prefixIcon, in a 48dp square at the start. [style] is the text's, which
 * the hint and prefix take, muted.
 *
 * An [error] turns the rule crit and shows under the field, as a form
 * validator's message does. A [label], as labelText, sits in the field
 * while it is empty and out of focus, and otherwise floats in a gap of
 * the top rule, small and in accent; the hint shows only then.
 */
@Composable
fun LabTextField(
    state: TextFieldState,
    style: TextStyle,
    modifier: Modifier = Modifier,
    hint: String? = null,
    prefix: String? = null,
    leadingIcon: Painter? = null,
    error: String? = null,
    label: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** What typing may change, as an input formatter */
    inputTransformation: InputTransformation? = null,
    /** What the keyboard's action key does, as onSubmitted; its default without */
    onKeyboardAction: KeyboardActionHandler? = null,
) {
    val p = LabTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val empty = state.text.isEmpty()
    val floating = label != null && (focused || !empty)
    val rule = when {
        error != null -> p.crit
        focused -> p.accent
        else -> p.rule
    }
    BasicTextField(
        state = state,
        modifier = if (error != null) modifier.semantics { error(error) } else modifier,
        textStyle = style,
        keyboardOptions = keyboardOptions,
        inputTransformation = inputTransformation,
        onKeyboardAction = onKeyboardAction,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        cursorBrush = SolidColor(p.accent),
        decorator = { input ->
            Column {
                Box {
                    Row(
                        Modifier
                            .background(p.paper)
                            .border(if (focused) 2.dp else 1.dp, rule)
                            .heightIn(min = 48.dp)
                            .padding(start = if (leadingIcon != null) 0.dp else TEXT_INSET, end = TEXT_INSET),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (leadingIcon != null) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(24.dp), tint = p.muted)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (prefix != null) {
                            Text(
                                text = prefix,
                                modifier = Modifier.alpha(if (empty && !focused) 0f else 1f),
                                style = style.copy(color = p.muted),
                            )
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(vertical = 14.dp),
                        ) {
                            when {
                                !empty -> Unit
                                label != null && !floating -> Text(label, style = style.copy(color = p.muted), maxLines = 1)
                                hint != null -> Text(hint, style = style.copy(color = p.muted), maxLines = 1)
                            }
                            input()
                        }
                    }
                    if (floating) FloatingLabel(label, color = if (error != null) p.crit else p.accent)
                }
                if (error != null) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(start = TEXT_INSET, top = 4.dp, end = TEXT_INSET),
                        style = MaterialTheme.typography.bodySmall.copy(color = p.crit),
                    )
                }
            }
        },
    )
}

/** Flutter's content padding, 14, and the 4 of gap an outline border adds */
private val TEXT_INSET = 18.dp

/** A label floated into the top rule: three quarters of the field's size, on paper */
@Composable
private fun FloatingLabel(text: String, color: Color) {
    val p = LabTheme.palette
    Text(
        text = text,
        modifier = Modifier
            .padding(start = TEXT_INSET - LABEL_GAP)
            // Centred on the rule, half above the field
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, -placeable.height / 2) }
            }
            .background(p.paper)
            .padding(horizontal = LABEL_GAP),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = color),
        maxLines = 1,
    )
}

/** The room OutlineInputBorder leaves around a floating label */
private val LABEL_GAP = 4.dp
