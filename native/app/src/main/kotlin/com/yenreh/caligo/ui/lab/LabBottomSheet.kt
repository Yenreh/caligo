package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
 * no shadow and no handle, the 2dp masthead rule along its top, over
 * Flutter's black54 barrier. It opens whole, however short its content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabBottomSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LabTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.drawWithContent {
            drawContent()
            // A Flutter shape border paints over the sheet's content
            drawRect(p.ink, Offset.Zero, Size(size.width, 2.dp.toPx()))
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = p.paper,
        contentColor = p.ink,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.54f),
        dragHandle = null,
        content = content,
    )
}
