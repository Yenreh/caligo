package com.yenreh.caligo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.R
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.theme.LabTheme

/** What the home screen lists, as the settings choose */
enum class HomeContent {
    CARDS, STOPS, BOTH;

    val showsCards get() = this != STOPS
}

/** Home dashboard; for now the masthead over the empty state */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    content: HomeContent = HomeContent.CARDS,
    onSettings: () -> Unit = {},
) {
    val p = LabTheme.palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(p.bg)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
    ) {
        Masthead(onSettings = onSettings)
        val cards = content.showsCards
        LabEmptyState(
            icon = painterResource(if (cards) R.drawable.ic_credit_card_off else R.drawable.ic_signpost),
            title = stringResource(if (cards) R.string.no_cards else R.string.no_favorite_stops),
            message = stringResource(
                if (cards) R.string.no_cards_message else R.string.no_favorite_stops_message,
            ),
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            ),
        )
    }
}

/** App name over the masthead rule */
@Composable
private fun Masthead(onSettings: () -> Unit) {
    val p = LabTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val rule = 2.dp.toPx()
                val y = size.height - rule / 2
                drawLine(p.ink, Offset(0f, y), Offset(size.width, y), rule)
            }
            .padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_directions_bus),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = p.accent,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = AppInfo.NAME,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        LabIconButton(
            icon = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.settings),
            onClick = onSettings,
            size = 40.dp,
        )
    }
}

@Preview(name = "Light", widthDp = 411, heightDp = 914)
@Composable
private fun HomeLightPreview() {
    CaliGoTheme(darkTheme = false) { HomeScreen() }
}

@Preview(name = "Dark", widthDp = 411, heightDp = 914)
@Composable
private fun HomeDarkPreview() {
    CaliGoTheme(darkTheme = true) { HomeScreen() }
}
