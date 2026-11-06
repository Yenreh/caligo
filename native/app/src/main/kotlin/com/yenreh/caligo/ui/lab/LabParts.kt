package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme

/** Uppercase, letter-spaced label that opens a section */
@Composable
fun LabSectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val p = LabTheme.palette
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = LabText.sectionLabel(p).let { if (color != null) it.copy(color = color) else it },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Paper panel framed by a rule, optionally opened by a 2dp accent rule.
 * The rule takes its own 1dp, as a Flutter border does.
 */
@Composable
fun LabPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    accentTop: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LabTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(p.paper)
            .border(1.dp, p.rule)
            .padding(1.dp),
    ) {
        if (accentTop) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(p.accent),
            )
        }
        Column(Modifier.padding(padding), content = content)
    }
}

/** Small outlined tag in mono type */
@Composable
fun LabChip(
    label: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    // A small filled square before the label, as a status marker
    marker: Boolean = false,
) {
    val p = LabTheme.palette
    val tone = color ?: p.muted
    Row(
        modifier = modifier
            .height(24.dp)
            .border(1.dp, color ?: p.rule)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marker) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(tone),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = LabText.mono(p, size = 12f, color = tone))
    }
}

/** Bus line tag: accent outline, mono, a minimum width so the destinations line up */
@Composable
fun LabLineChip(label: String, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    Box(
        modifier = modifier
            .widthIn(min = 48.dp)
            .border(1.dp, p.accent)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, textAlign = TextAlign.Center, style = LabText.mono(p, size = 13f, color = p.accent))
    }
}

/** A rule between rows, as the Flutter theme's divider */
@Composable
fun LabDivider(modifier: Modifier = Modifier, indent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = indent),
        thickness = 1.dp,
        color = LabTheme.palette.ruleSoft,
    )
}

/** [text] on a long press, below [content], as a Flutter tooltip shows it */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LabTheme.palette
    // TooltipBox sets its modifier on an inner box: the outer one takes it
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = {
                PlainTooltip(shape = RectangleShape, containerColor = p.ink, contentColor = p.bg) {
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(color = p.bg))
                }
            },
            state = rememberTooltipState(),
            content = content,
        )
    }
}
