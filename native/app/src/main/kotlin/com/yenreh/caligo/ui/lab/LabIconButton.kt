package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * Square outlined icon button, named by its description, which also shows
 * as its tooltip. While [isLoading] it spins and takes no taps; not
 * [enabled], it looks the same and takes none either, as a Flutter one
 * without onPressed.
 */
@Composable
fun LabIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true,
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
    LabTooltip(contentDescription, modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .semantics { this.contentDescription = contentDescription },
            enabled = enabled && !isLoading,
            shape = RectangleShape,
            color = p.paper,
            border = BorderStroke(1.dp, p.rule),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = p.accent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = tone,
                    )
                }
            }
        }
    }
}
