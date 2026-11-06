package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/** Text button in accent, square, as the Flutter theme's */
@Composable
fun LabTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RectangleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = LabTheme.palette.accent),
        content = content,
    )
}

/** Outlined button in ink on a rule, the rule turning accent while pressed */
@Composable
fun LabOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val p = LabTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RectangleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
        border = BorderStroke(1.dp, if (pressed) p.accent else p.rule),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content,
    )
}

/**
 * The one primary action: fill ink, square, fading when disabled, the
 * accent washing over it while pressed, as the Flutter theme's
 */
@Composable
fun LabFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val p = LabTheme.palette
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = p.fill,
            contentColor = p.onFill,
            disabledContainerColor = p.fill.copy(alpha = 0.4f),
            disabledContentColor = p.onFill.copy(alpha = 0.6f),
        ),
        elevation = null,
        contentPadding = contentPadding,
        content = content,
    )
}
