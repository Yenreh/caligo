package com.yenreh.caligo.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * The cards screen with its data: the activity's [CardsViewModel], which
 * the home shares, and the refreshes' outcome in snackbars
 */
@Composable
fun CardsRoute(
    settings: Settings,
    viewModel: CardsViewModel,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (cardId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val snackbars = rememberLabSnackbars()

    RefreshBalancesOnOpen(settings, cards, viewModel)
    ListenCardRefresh(viewModel, snackbars)

    CardsScreen(
        cards = cards,
        farePrice = settings.farePrice,
        modifier = modifier,
        snackbars = snackbars,
        actions = CardsActions(
            onBack = onBack,
            onRefreshAll = { viewModel.refreshAllBalances() },
            onRefreshCard = { viewModel.refreshCardBalance(it) },
            onCreate = onCreate,
            onEdit = onEdit,
            onDelete = { viewModel.deleteCardById(it) },
            onReorder = { from, to -> viewModel.reorderCards(from, to) },
        ),
    )
}

/**
 * The create screen: once the number and the name are there, the card is
 * saved and the screen closes through [onDone]. The new card's balance is
 * not asked for here; the refresh on opening, if still due, does it.
 */
@Composable
fun CreateCardRoute(
    viewModel: CardsViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = rememberCardForm { CardForm.forNewCard() }
    val snackbars = rememberLabSnackbars()
    val save = rememberSave(viewModel, snackbars, R.string.create_card_error, onDone)

    CardFormScreen(
        title = stringResource(R.string.create_card_title),
        buttonLabel = stringResource(R.string.create_card_button),
        form = form,
        isSaving = save.isSaving,
        onSubmit = {
            if (!save.isSaving && form.validate()) {
                save.run {
                    viewModel.createCard(
                        id = form.trimmedId,
                        name = form.trimmedName,
                        prefix = form.trimmedPrefix,
                        suffix = form.trimmedSuffix,
                    )
                }
            }
        },
        onBack = onBack,
        modifier = modifier,
        snackbars = snackbars,
    )
}

/**
 * The edit screen for the card numbered [cardId]: its fields filled once
 * from it, a new number checked against the other cards', and the screen
 * closed through [onDone] once saved, or once the card is found gone.
 */
@Composable
fun EditCardRoute(
    cardId: String,
    viewModel: CardsViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.cards.collectAsStateWithLifecycle()
    val card = state.cards.firstOrNull { it.id == cardId }
    val form = rememberCardForm { CardForm().apply { card?.let(::load) } }
    val snackbars = rememberLabSnackbars()
    val save = rememberSave(viewModel, snackbars, R.string.save_changes_error, onDone)

    // Filled once, as soon as the card is there: never over the user's edits
    LaunchedEffect(card) {
        if (card != null && form.originalId == null) form.load(card)
    }

    // Once filled, the form stays while a new number takes the card out of
    // this route's reach as it closes
    if (card == null && form.originalId == null) {
        CardMissingScreen(onBack = onBack, modifier = modifier)
        return
    }

    CardFormScreen(
        title = stringResource(R.string.edit_card_title),
        buttonLabel = stringResource(R.string.save_changes),
        form = form,
        isSaving = save.isSaving,
        onSubmit = submit@{
            if (save.isSaving) return@submit
            val cards = viewModel.cards.value.cards
            val valid = form.validate { number -> number != form.originalId && cards.any { it.id == number } }
            if (!valid) return@submit
            val current = cards.firstOrNull { it.id == cardId }
            if (current == null) {
                onDone()
                return@submit
            }
            save.run {
                viewModel.updateCard(
                    current.copy(
                        id = form.trimmedId,
                        name = form.trimmedName,
                        prefix = form.trimmedPrefix,
                        suffix = form.trimmedSuffix,
                    ),
                )
            }
        },
        onBack = onBack,
        modifier = modifier,
        snackbars = snackbars,
    )
}

/** A save in flight and how it ends: [onDone] when it took, else [failure] */
private class Save(
    private val scope: CoroutineScope,
    private val viewModel: CardsViewModel,
    private val snackbars: LabSnackbars,
    private val failure: String,
    private val onDone: () -> Unit,
) {
    var isSaving by mutableStateOf(false)
        private set

    fun run(save: () -> Deferred<Boolean>) {
        isSaving = true
        val saving = save()
        // Cancelled with the screen, as Flutter checks mounted: the save
        // itself goes on in the view model
        scope.launch {
            val saved = saving.await()
            isSaving = false
            if (saved) {
                onDone()
            } else {
                // Reported here, not as a failed balance update elsewhere
                viewModel.clearError()
                snackbars.show(failure, tone = null)
            }
        }
    }
}

@Composable
private fun rememberSave(viewModel: CardsViewModel, snackbars: LabSnackbars, failure: Int, onDone: () -> Unit): Save {
    val scope = rememberCoroutineScope()
    val message = stringResource(failure)
    val done = rememberUpdatedState(onDone)
    return remember(scope, viewModel, snackbars, message) {
        Save(scope, viewModel, snackbars, message) { done.value() }
    }
}
