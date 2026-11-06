package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/** Square outlined icon button, named by its description */
@Composable
fun LabIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    // Drawn in accent, for a toggle that is on
    active: Boolean = false,
    size: Dp = 36.dp,
) {
    val p = LabTheme.palette
    val tone = when {
        danger -> p.crit
        active -> p.accent
        else -> p.muted
    }
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = RectangleShape,
        color = p.paper,
        border = BorderStroke(1.dp, p.rule),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = tone,
            )
        }
    }
}
