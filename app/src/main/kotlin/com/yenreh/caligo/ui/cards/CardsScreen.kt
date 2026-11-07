package com.yenreh.caligo.ui.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.lab.LabBottomSheet
import com.yenreh.caligo.ui.lab.LabEmptyState
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabFloatingActionButton
import com.yenreh.caligo.ui.lab.LabOutlinedButton
import com.yenreh.caligo.ui.lab.LabReorderableList
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.labBottomInset
import com.yenreh.caligo.ui.lab.labSideInsets
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.theme.LabTheme

/** What the cards screen's buttons and gestures do */
class CardsActions(
    val onBack: () -> Unit = {},
    val onRefreshAll: () -> Unit = {},
    val onRefreshCard: (cardId: String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onEdit: (cardId: String) -> Unit = {},
    /** Once confirmed in the sheet */
    val onDelete: (cardId: String) -> Unit = {},
    /** With Flutter's indexes, as [CardsViewModel.reorderCards] takes them */
    val onReorder: (oldIndex: Int, newIndex: Int) -> Unit = { _, _ -> },
)

/**
 * Manage the transport cards: create, refresh, edit, delete, and hold
 * one and drag it to change the order
 */
@Composable
fun CardsScreen(
    cards: CardsState,
    farePrice: Int,
    modifier: Modifier = Modifier,
    actions: CardsActions = CardsActions(),
    snackbars: LabSnackbars = rememberLabSnackbars(),
) {
    val p = LabTheme.palette
    // The card the delete sheet asks about, by internal id
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LabTopBar(stringResource(R.string.my_cards), onBack = actions.onBack) {
                RefreshAllAction(cards, actions.onRefreshAll)
            }
        },
        floatingActionButton = {
            LabFloatingActionButton(
                icon = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.new_card),
                onClick = actions.onCreate,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
            )
        },
        // Above the button, as Flutter's Scaffold puts a floating snackbar,
        // the button below it already clear of the system bars
        snackbarHost = {
            LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)))
        },
    ) { padding ->
        val body = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
            .fillMaxSize()
        when {
            cards.isLoading && cards.cards.isEmpty() -> Box(body, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(36.dp), color = p.accent, strokeWidth = 4.dp)
            }
            cards.cards.isEmpty() -> LabEmptyState(
                icon = painterResource(R.drawable.ic_credit_card_off),
                title = stringResource(R.string.no_cards),
                message = stringResource(R.string.no_cards_message),
                actionLabel = stringResource(R.string.create_first_card),
                actionIcon = painterResource(R.drawable.ic_add),
                onAction = actions.onCreate,
                modifier = body.testTag("cards_empty"),
            )
            else -> {
                val bottom = labBottomInset()
                LabReorderableList(
                    items = cards.cards,
                    // The number repeats if two cards share it; the internal id never does
                    key = { it.internalId },
                    onReorder = actions.onReorder,
                    modifier = body
                        .windowInsetsPadding(labSideInsets())
                        .testTag("cards_list"),
                    // Room at the end for the button floating over the list
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp + bottom),
                ) { card ->
                    CardItem(
                        card = card,
                        isRefreshing = cards.refreshingCardId == card.id,
                        refreshFailed = cards.refreshFailedCardId == card.id,
                        farePrice = farePrice,
                        onRefreshClick = { actions.onRefreshCard(card.id) },
                        onEditClick = { actions.onEdit(card.id) },
                        onDeleteClick = { deleting = card.internalId },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }

    val asked = cards.cards.firstOrNull { it.internalId == deleting }
    if (asked != null) {
        DeleteCardSheet(
            card = asked,
            onConfirm = {
                deleting = null
                actions.onDelete(asked.id)
            },
            onCancel = { deleting = null },
        )
    }
}

/** A spinner while every card refreshes, else the button that starts it */
@Composable
private fun RefreshAllAction(cards: CardsState, onRefreshAll: () -> Unit) {
    val p = LabTheme.palette
    when {
        cards.isRefreshingAll -> Box(
            Modifier
                .padding(horizontal = 20.dp)
                .size(18.dp)
                .testTag("refreshing_all"),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
        }
        cards.cards.isNotEmpty() -> {
            val label = stringResource(R.string.update_balance)
            LabTooltip(label) {
                IconButton(
                    onClick = onRefreshAll,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("refresh_all"),
                    shape = RectangleShape,
                ) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = label, tint = p.ink)
                }
            }
        }
    }
}

/** Asks before a card goes, naming it */
@Composable
private fun DeleteCardSheet(card: CardEntity, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val p = LabTheme.palette
    LabBottomSheet(onDismiss = onCancel) {
        Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp)) {
            LabSectionLabel(stringResource(R.string.delete), color = p.crit)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.delete_card_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.delete_card_message, card.name),
                style = MaterialTheme.typography.bodyMedium.copy(color = p.muted),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                LabOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                LabFilledButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    containerColor = p.crit,
                    contentColor = p.bg,
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

@Preview(name = "Light", widthDp = 411, heightDp = 914)
@Composable
private fun CardsLightPreview() {
    CaliGoTheme(darkTheme = false) {
        CardsScreen(
            CardsState(cards = listOf(CardEntity(internalId = "1", id = "06051868", name = "Mía", balance = 15300.0))),
            farePrice = Settings.DEFAULT_FARE_PRICE,
        )
    }
}

@Preview(name = "Dark", widthDp = 411, heightDp = 914)
@Composable
private fun CardsDarkPreview() {
    CaliGoTheme(darkTheme = true) {
        CardsScreen(CardsState(), farePrice = Settings.DEFAULT_FARE_PRICE)
    }
}
