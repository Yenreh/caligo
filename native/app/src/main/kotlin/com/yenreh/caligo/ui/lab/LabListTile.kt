package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * A row as the Flutter theme's Material 3 ListTile lays it out: 16 before
 * and 24 after, 8 above and below, at least 56 tall or 72 with a subtitle,
 * leading and trailing centred, 16 between them and the text.
 *
 * Taps are the caller's: [modifier] carries the clickable, toggleable or
 * selectable that fits, so the row says what it does.
 */
@Composable
fun LabListTile(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: Painter? = null,
    leadingTint: Color? = null,
    titleWeight: FontWeight = FontWeight.Medium,
    /** Title, subtitle and leading icon in one colour, as a selected Flutter tile has them */
    contentColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LabTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) 72.dp else 56.dp)
            .padding(start = 16.dp, top = 8.dp, end = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(24.dp), tint = contentColor ?: leadingTint ?: p.muted)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = titleWeight, color = contentColor ?: p.ink),
            )
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium.copy(color = contentColor ?: p.muted))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}
