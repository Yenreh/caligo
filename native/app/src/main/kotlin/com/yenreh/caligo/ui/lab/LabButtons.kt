package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/** Text button in accent, square, as the Flutter theme's */
@Composable
fun LabTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
 * accent washing over it while pressed, as the Flutter theme's.
 * [containerColor] and [contentColor] replace the fill, as a destructive
 * action does with crit.
 */
@Composable
fun LabFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    containerColor: Color = LabTheme.palette.fill,
    contentColor: Color = LabTheme.palette.onFill,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.6f),
        ),
        elevation = null,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * The floating action button of the Flutter theme: 56dp, square, fill
 * and no shadow, named by its tooltip
 */
@Composable
fun LabFloatingActionButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LabTheme.palette
    LabTooltip(contentDescription, modifier) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
            shape = RectangleShape,
            containerColor = p.fill,
            contentColor = p.onFill,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}
