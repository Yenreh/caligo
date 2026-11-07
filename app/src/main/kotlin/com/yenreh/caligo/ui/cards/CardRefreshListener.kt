package com.yenreh.caligo.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import com.yenreh.caligo.R
import com.yenreh.caligo.data.datasources.ApiException
import com.yenreh.caligo.data.datasources.CardNotFoundApiException
import com.yenreh.caligo.data.datasources.InvalidCardApiException
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.datasources.RateLimitApiException
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A typed API error, as the user reads it */
private fun cardErrorMessage(error: ApiException): Int = when (error) {
    is NetworkApiException -> R.string.network_error_message
    is ServerApiException -> R.string.server_error_message
    is InvalidCardApiException -> R.string.invalid_card_message
    is CardNotFoundApiException -> R.string.card_not_found_message
    is RateLimitApiException -> R.string.rate_limit_message
    else -> R.string.could_not_update_balance
}

/**
 * Report the outcome of a balance refresh, wherever the cards are shown.
 * Only changes count, as with Riverpod's ref.listen: the state the screen
 * finds on opening, such as a success another screen already announced,
 * is not announced again.
 */
@Composable
fun ListenCardRefresh(viewModel: CardsViewModel, snackbars: LabSnackbars) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbars, resources) {
        var previous: CardsState? = null
        viewModel.cards.collect { next ->
            val before = previous
            previous = next
            if (before == null) return@collect

            if (next.lastRefreshSuccess && !before.lastRefreshSuccess) {
                snackbars.show(resources.getString(R.string.balance_updated), LabTone.OK, duration = 1.seconds)
            }

            val refreshError = next.refreshError
            if (refreshError != null && refreshError != before.refreshError) {
                val failedCardId = next.refreshFailedCardId
                snackbars.show(
                    resources.getString(cardErrorMessage(refreshError)),
                    LabTone.CRIT,
                    actionLabel = failedCardId?.let { resources.getString(R.string.retry) },
                    onAction = { if (failedCardId != null) viewModel.refreshCardBalance(failedCardId) },
                )
                // The message is let go after a beat, so the same error can
                // show again; the card stays marked until its next refresh
                launch {
                    delay(100.milliseconds)
                    viewModel.clearRefreshMessage()
                }
            }

            if (next.error != null && next.error != before.error) {
                val message = when (next.failedAction) {
                    CardAction.REORDER -> R.string.could_not_reorder_cards
                    CardAction.DELETE -> R.string.could_not_delete_card
                    null -> R.string.could_not_update_balance
                }
                snackbars.show(resources.getString(message), LabTone.CRIT, duration = 2.seconds)
                launch {
                    delay(100.milliseconds)
                    viewModel.clearError()
                }
            }
        }
    }
}

/**
 * Every balance, once, as the app opens: not on coming back from the
 * background or from another screen. Done once the settings and the
 * cards have loaded.
 *
 * The Flutter home decides it on each of its builds, which go on under
 * the screens pushed over it: a launch that found no cards refreshes the
 * first one created. The cards screen runs it too, for the same result.
 */
@Composable
fun RefreshBalancesOnOpen(settings: Settings, cards: CardsState, viewModel: CardsViewModel) {
    LaunchedEffect(settings, cards.isLoading, cards.cards) {
        if (!viewModel.openRefreshPending || settings.isLoading) return@LaunchedEffect
        if (!settings.refreshBalancesOnOpen || !settings.showsCards) {
            viewModel.openRefreshPending = false
            return@LaunchedEffect
        }
        if (cards.isLoading) return@LaunchedEffect
        // False until the cards are there to refresh
        if (viewModel.refreshOnOpen()) viewModel.openRefreshPending = false
    }
}
