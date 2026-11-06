package com.yenreh.caligo.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.R
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * The Flutter theme's app bar: paper behind the status bar, a back arrow,
 * the title in serif and the 2dp masthead rule along the bottom, drawn
 * inside the bar's 56dp as a Flutter shape border is. [subtitle] goes
 * under the title, as the license page puts its count. [actions] sit at
 * the end, as an AppBar's, flush with the edge.
 */
@Composable
fun LabTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = LabTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(p.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(56.dp)
            .drawBehind {
                val rule = 2.dp.toPx()
                val y = size.height - rule / 2
                drawLine(p.ink, Offset(0f, y), Offset(size.width, y), rule)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Flutter's leading slot is 56 wide, the button 48 in its middle
        Spacer(Modifier.width(4.dp))
        val back = stringResource(R.string.back)
        LabTooltip(back) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(onClickLabel = back, role = Role.Button, onClick = onBack)
                    .semantics { this.contentDescription = back },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = p.ink)
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, color = p.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleSmall.copy(color = p.ink),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        actions?.invoke(this)
    }
}
