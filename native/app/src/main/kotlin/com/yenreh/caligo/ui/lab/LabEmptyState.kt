package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * Nothing to show yet: serif title, muted hint, and an action when
 * [actionLabel] and [onAction] are given: filled, with [actionIcon] if
 * any, or outlined when [secondaryAction], for a retry rather than a
 * first step
 */
@Composable
fun LabEmptyState(
    icon: Painter,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    actionIcon: Painter? = null,
    onAction: (() -> Unit)? = null,
    secondaryAction: Boolean = false,
) {
    val p = LabTheme.palette
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = p.muted.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            if (title != null) {
                Text(
                    text = title,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = message,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = p.muted,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(24.dp))
                when {
                    secondaryAction -> LabOutlinedButton(onClick = onAction) { Text(actionLabel) }
                    else -> LabFilledButton(onClick = onAction) {
                        if (actionIcon != null) {
                            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
