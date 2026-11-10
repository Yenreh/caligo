package com.yenreh.caligo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.cards.CardItem
import com.yenreh.caligo.ui.cards.CardsState
import com.yenreh.caligo.ui.common.LiveClock
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.lab.LabOutlinedButton
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.stops.FavoriteStopCard
import com.yenreh.caligo.ui.stops.StopsState
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.theme.LabTheme

/** Where the home's buttons lead; each does nothing unless given */
class HomeActions(
    val onSettings: () -> Unit = {},
    /** The cards screen: "See all" and the bar's cards button */
    val onCards: () -> Unit = {},
    /** The add-stop screen, to look around: the bar's search button */
    val onSearch: () -> Unit = {},
    /** The trip planner: the bar's plan button */
    val onPlan: () -> Unit = {},
    /** The stops screen: "See all" and the bar's favorites button */
    val onStops: () -> Unit = {},
)

/**
 * Home dashboard: whatever the settings ask for, cards, stops or both,
 * with the management screens a button away at the bottom.
 *
 * [stops] is null while the home shows no stops, which never loads them.
 */
@Composable
fun HomeScreen(
    settings: Settings,
    cards: CardsState,
    stops: StopsState?,
    modifier: Modifier = Modifier,
    onRefreshCard: (cardId: String) -> Unit = {},
    onRefreshAllCards: () -> Unit = {},
    onRefreshStops: () -> Unit = {},
    actions: HomeActions = HomeActions(),
    snackbars: LabSnackbars = rememberLabSnackbars(),
) {
    val p = LabTheme.palette
    val showsCards = settings.showsCards
    val showsStops = settings.showsStops
    val homeStops = if (showsStops) stops?.favorites.orEmpty().filter { it.showOnHome } else emptyList()
    // Condensed cards leave room for the stops below them
    val compactCards = showsCards && showsStops && cards.cards.size > 1
    val isEmpty = (!showsCards || cards.cards.isEmpty()) && homeStops.isEmpty()
    // Whatever the home lists, still on its way: no "nothing saved" before it is known
    val cardsLoading = showsCards && cards.isLoading && cards.cards.isEmpty()
    val stopsLoading = showsStops && stops?.isLoading == true && homeStops.isEmpty()

    LiveClock {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                // Lets UI Automator find the tagged parts, for the Baseline Profile
                .semantics { testTagsAsResourceId = true },
            containerColor = p.bg,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { LabSnackbarHost(snackbars) },
            bottomBar = { ManageBar(actions) },
        ) { padding ->
            val body = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            when {
                cardsLoading || (isEmpty && stopsLoading) -> Column(body) {
                    Masthead(actions.onSettings)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = p.accent, strokeWidth = 4.dp)
                    }
                }
                isEmpty -> Column(body) {
                    Masthead(actions.onSettings)
                    LabEmptyState(
                        icon = painterResource(if (showsCards) R.drawable.ic_credit_card_off else R.drawable.ic_signpost),
                        title = stringResource(if (showsCards) R.string.no_cards else R.string.no_favorite_stops),
                        message = stringResource(
                            if (showsCards) R.string.no_cards_message else R.string.no_favorite_stops_message,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_empty"),
                    )
                }
                else -> LazyColumn(body.testTag("home_list")) {
                    item { Masthead(actions.onSettings) }
                    if (showsCards) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.my_cards),
                                isRefreshing = cards.isRefreshingAll,
                                onRefresh = onRefreshAllCards,
                                onAction = actions.onCards,
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                        items(cards.cards, key = { "card:${it.internalId}" }) { card ->
                            CardItem(
                                card = card,
                                isRefreshing = cards.refreshingCardId == card.id,
                                refreshFailed = cards.refreshFailedCardId == card.id,
                                farePrice = settings.farePrice,
                                compact = compactCards,
                                onRefreshClick = { onRefreshCard(card.id) },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = if (compactCards) 8.dp else 12.dp),
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                    if (showsStops) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.favorite_stops),
                                isRefreshing = stops?.isRefreshing == true,
                                onRefresh = onRefreshStops,
                                onAction = actions.onStops,
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                        items(homeStops, key = { "stop:${it.id}" }) { stop ->
                            FavoriteStopCard(
                                stop = stop,
                                arrivals = stops?.arrivals?.get(stop.id),
                                fading = stops?.fading?.get(stop.id),
                                isLoading = stops?.isRefreshing == true,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
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
            // The rule takes its own 2dp below the padding, as a Flutter border
            .padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 14.dp),
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

/** Section title, with a way to refresh it and a link to its screen */
@Composable
private fun SectionHeader(title: String, isRefreshing: Boolean, onRefresh: () -> Unit, onAction: () -> Unit) {
    val p = LabTheme.palette
    Row(
        modifier = Modifier
            .padding(start = 20.dp, top = 16.dp, end = 12.dp)
            .fillMaxWidth()
            .drawBehind {
                val rule = 1.dp.toPx()
                val y = size.height - rule / 2
                drawLine(p.rule, Offset(0f, y), Offset(size.width, y), rule)
            }
            // 2dp of padding, then the rule's own 1dp
            .padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabSectionLabel(title, Modifier.weight(1f))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (isRefreshing) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
            } else {
                LabTooltip(title) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp), shape = RectangleShape) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = title,
                            modifier = Modifier.size(20.dp),
                            tint = p.muted,
                        )
                    }
                }
            }
        }
        LabTextButton(onClick = onAction) { Text(stringResource(R.string.see_all)) }
    }
}

/**
 * The two management screens with the stop search and the trip planner
 * between them, always in reach. Icons only, so all four share one size.
 */
@Composable
private fun ManageBar(actions: HomeActions) {
    val p = LabTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.paper)
            .drawBehind {
                val rule = 1.dp.toPx()
                drawLine(p.rule, Offset(0f, rule / 2), Offset(size.width, rule / 2), rule)
            }
            .padding(top = 1.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(48.dp),
    ) {
        BarButton(painterResource(R.drawable.ic_credit_card), stringResource(R.string.cards_short), actions.onCards)
        Spacer(Modifier.width(8.dp))
        BarButton(painterResource(R.drawable.ic_search), stringResource(R.string.find_stops), actions.onSearch)
        Spacer(Modifier.width(8.dp))
        BarButton(painterResource(R.drawable.ic_directions), stringResource(R.string.plan_trip), actions.onPlan)
        Spacer(Modifier.width(8.dp))
        BarButton(painterResource(R.drawable.ic_signpost), stringResource(R.string.favorites_short), actions.onStops)
    }
}

/** A bar button: an icon, named by its tooltip */
@Composable
private fun RowScope.BarButton(icon: Painter, tooltip: String, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight(),
    ) {
        LabTooltip(tooltip) {
            LabOutlinedButton(onClick = onClick, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(0.dp)) {
                Icon(icon, contentDescription = tooltip, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Preview(name = "Light", widthDp = 411, heightDp = 914)
@Composable
private fun HomeLightPreview() {
    CaliGoTheme(darkTheme = false) {
        HomeScreen(Settings(isLoading = false), CardsState(), stops = null)
    }
}

@Preview(name = "Dark", widthDp = 411, heightDp = 914)
@Composable
private fun HomeDarkPreview() {
    CaliGoTheme(darkTheme = true) {
        HomeScreen(Settings(isLoading = false, homeContent = HomeContent.STOPS), CardsState(), StopsState())
    }
}
