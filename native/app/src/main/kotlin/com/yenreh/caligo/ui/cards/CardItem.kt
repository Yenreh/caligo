package com.yenreh.caligo.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.ui.lab.LabChip
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabIconButton
import com.yenreh.caligo.ui.lab.LabPanel
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Transport card as a lab panel: number, name, balance and fares. The
 * home lists it with the refresh alone; the cards screen adds editing
 * and deleting, given [onEditClick] and [onDeleteClick], beside it.
 */
@Composable
fun CardItem(
    card: CardEntity,
    isRefreshing: Boolean,
    farePrice: Int,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshFailed: Boolean = false,
    // Shorter, so more cards fit when the home also shows the stops
    compact: Boolean = false,
    onEditClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val fares = availableFares(card.balance, farePrice)
    val stale = refreshFailed && card.balance != null
    if (compact) {
        CompactCard(card, isRefreshing, fares, stale, onRefreshClick, modifier.testTag("card"))
        return
    }

    val p = LabTheme.palette
    val showFares = card.balance != null && fares > 0
    LabPanel(modifier = modifier.testTag("card"), accentTop = true, padding = PaddingValues(0.dp)) {
        // Card number and refresh
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatCardNumber(card.displayId),
                modifier = Modifier.weight(1f),
                style = LabText.mono(p, size = 16f).copy(letterSpacing = 1.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RefreshButton(isRefreshing, onRefreshClick)
            if (onEditClick != null && onDeleteClick != null) {
                Spacer(Modifier.width(6.dp))
                LabIconButton(
                    icon = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    onClick = onEditClick,
                )
                Spacer(Modifier.width(6.dp))
                LabIconButton(
                    icon = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.delete),
                    onClick = onDeleteClick,
                    danger = true,
                )
            }
        }
        LabDivider()

        // Name and balance
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                LabSectionLabel(stringResource(R.string.name))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                LabSectionLabel(stringResource(R.string.balance))
                Spacer(Modifier.height(2.dp))
                Balance(card.balance, compact = false)
            }
        }

        // Status line: stale notice or last update, and the fares left
        val lastUpdate = card.lastUpdate
        if (showFares || stale || lastUpdate != null) {
            LabDivider()
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val style = LabText.statusLine(p).copy(fontSize = 13.sp)
                Row(Modifier.weight(1f)) {
                    when {
                        stale -> Text(
                            stringResource(R.string.stale_balance_notice),
                            style = style.copy(color = p.warn),
                        )
                        lastUpdate != null -> Text(
                            stringResource(R.string.updated_at, formatDateTime(lastUpdate)),
                            style = style,
                        )
                    }
                }
                if (showFares) {
                    Spacer(Modifier.width(12.dp))
                    LabChip(pluralStringResource(R.plurals.fares, fares, fares).uppercase())
                }
            }
        }
    }
}

/** Condensed card: name, number and balance on a single row */
@Composable
private fun CompactCard(
    card: CardEntity,
    isRefreshing: Boolean,
    fares: Int,
    stale: Boolean,
    onRefreshClick: () -> Unit,
    modifier: Modifier,
) {
    val p = LabTheme.palette
    val edge = if (stale) p.warn else p.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(p.paper)
            .drawBehind {
                // A rule on three sides and a 2dp edge on the left
                val rule = 1.dp.toPx()
                val left = 2.dp.toPx()
                drawRect(p.rule, Offset(0f, 0f), Size(size.width, rule))
                drawRect(p.rule, Offset(0f, size.height - rule), Size(size.width, rule))
                drawRect(p.rule, Offset(size.width - rule, 0f), Size(rule, size.height))
                drawRect(edge, Offset(0f, 0f), Size(left, size.height))
            }
            // The borders take their own room, as Flutter's do
            .padding(start = 16.dp, top = 11.dp, end = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatCardNumber(card.displayId),
                style = LabText.mono(p, size = 12f, color = p.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Balance(card.balance, compact = true)
            if (card.balance != null && fares > 0) {
                Text(
                    pluralStringResource(R.plurals.fares, fares, fares),
                    style = LabText.mono(p, size = 11f, color = p.muted),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        RefreshButton(isRefreshing, onRefreshClick)
    }
}

@Composable
private fun RefreshButton(isRefreshing: Boolean, onClick: () -> Unit) {
    LabIconButton(
        icon = painterResource(R.drawable.ic_refresh),
        contentDescription = stringResource(R.string.update_balance),
        onClick = onClick,
        isLoading = isRefreshing,
    )
}

/** Balance in mono figures: ink when in credit, crit when overdrawn */
@Composable
private fun Balance(balance: Double?, compact: Boolean) {
    val p = LabTheme.palette
    val size = if (compact) 17f else 26f
    if (balance == null) {
        Text("---", style = LabText.mono(p, size = size, color = p.muted))
    } else {
        Text(formatBalance(balance), style = LabText.mono(p, size = size, color = if (balance >= 0) p.ink else p.crit))
    }
}

/** How many fares [balance] covers, none without a balance or a price */
internal fun availableFares(balance: Double?, farePrice: Int): Int {
    if (balance == null || balance <= 0 || farePrice <= 0) return 0
    return floor(balance / farePrice).toInt()
}

/** The number in groups of four, for reading */
internal fun formatCardNumber(number: String): String =
    number.replace(" ", "").chunked(4).joinToString(" ")

/**
 * Pesos as intl's es_CO currency format gives them with no decimals:
 * dots between thousands, rounded half away from zero, then a no-break
 * space and the sign, "15.300 $"
 */
internal fun formatBalance(balance: Double): String {
    val rounded = floor(abs(balance) + 0.5).toLong()
    val digits = rounded.toString().reversed().chunked(3).joinToString(".").reversed()
    // Negative zero included, as Dart's isNegative counts it
    val sign = if (balance < 0 || 1 / balance < 0) "-" else ""
    return "$sign$digits $"
}

/** Day, month, year and 24-hour time, as the Flutter app's DateUtils */
internal fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date(epochMillis))
