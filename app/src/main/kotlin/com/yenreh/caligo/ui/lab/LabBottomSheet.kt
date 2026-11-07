package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * A modal bottom sheet as the Flutter theme draws one: paper, square,
 * no shadow, the 2dp masthead rule along its top, over Flutter's black54
 * barrier. It opens whole, however short its content. With [dragHandle],
 * as showDragHandle, a short rule-coloured bar in a 48dp band above the
 * content; without, no handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LabTheme.palette
    // A Flutter shape border paints over the sheet's content. Drawn by
    // what sits at the top, the handle or the content: the sheet's own
    // modifier spans the whole window, the rule then at the screen's top
    val rule = Modifier.drawWithContent {
        drawContent()
        drawRect(p.ink, Offset.Zero, Size(size.width, 2.dp.toPx()))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = p.paper,
        contentColor = p.ink,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.54f),
        dragHandle = if (dragHandle) {
            { DragHandle(rule) }
        } else {
            null
        },
    ) {
        Column(if (dragHandle) Modifier else rule, content = content)
    }
}

/** The theme's handle: 36 by 3, in rule, rounded, centred in 48 */
@Composable
private fun DragHandle(modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 3.dp)
                .background(LabTheme.palette.rule, RoundedCornerShape(1.5.dp)),
        )
    }
}
