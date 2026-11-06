package com.yenreh.caligo.ui.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.CardEntity
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabSectionLabel
import com.yenreh.caligo.ui.lab.LabSnackbarHost
import com.yenreh.caligo.ui.lab.LabSnackbars
import com.yenreh.caligo.ui.lab.LabTextField
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.theme.CaliGoTheme
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * The fields of a card being created or edited, and what their
 * validators said at the last submit: as with a Flutter Form, a message
 * stays until the next one, however the field changes meanwhile.
 */
@Stable
class CardForm(id: String = "", name: String = "", prefix: String = "", suffix: String = "") {
    val id = TextFieldState(id)
    val name = TextFieldState(name)
    val prefix = TextFieldState(prefix)
    val suffix = TextFieldState(suffix)

    @get:StringRes
    var idError: Int? by mutableStateOf(null)
        private set

    @get:StringRes
    var nameError: Int? by mutableStateOf(null)
        private set

    /** The number of the card being edited, once its fields were filled from it */
    var originalId: String? by mutableStateOf(null)
        private set

    /** Fill the fields from [card], the one being edited */
    fun load(card: CardEntity) {
        id.setTextAndPlaceCursorAtEnd(card.id)
        name.setTextAndPlaceCursorAtEnd(card.name)
        prefix.setTextAndPlaceCursorAtEnd(card.prefix)
        suffix.setTextAndPlaceCursorAtEnd(card.suffix)
        originalId = card.id
    }

    /**
     * Run every validator, as a form's validate: the number and the name
     * are required, and the number may not be [taken]. True when all pass.
     */
    fun validate(taken: (String) -> Boolean = { false }): Boolean {
        val number = id.text.trim().toString()
        idError = when {
            number.isEmpty() -> R.string.id_required
            taken(number) -> R.string.card_id_exists
            else -> null
        }
        nameError = if (name.text.isBlank()) R.string.name_required else null
        return idError == null && nameError == null
    }

    val trimmedId get() = id.text.trim().toString()
    val trimmedName get() = name.text.trim().toString()
    val trimmedPrefix get() = prefix.text.trim().toString()
    val trimmedSuffix get() = suffix.text.trim().toString()

    companion object {
        /** A new card starts with the prefix and suffix most cards carry */
        fun forNewCard() = CardForm(prefix = "1906", suffix = "1")

        val Saver = listSaver<CardForm, Any?>(
            save = {
                listOf(
                    it.id.text.toString(),
                    it.name.text.toString(),
                    it.prefix.text.toString(),
                    it.suffix.text.toString(),
                    it.idError,
                    it.nameError,
                    it.originalId,
                )
            },
            restore = {
                CardForm(it[0] as String, it[1] as String, it[2] as String, it[3] as String).apply {
                    idError = it[4] as Int?
                    nameError = it[5] as Int?
                    originalId = it[6] as String?
                }
            },
        )
    }
}

/** A [CardForm] that outlives configuration changes and process death */
@Composable
fun rememberCardForm(initial: () -> CardForm): CardForm = rememberSaveable(saver = CardForm.Saver, init = initial)

/**
 * The form the create and edit screens share: number, name, prefix and
 * suffix, and the button that submits them, spinning while [isSaving]
 */
@Composable
fun CardFormScreen(
    title: String,
    buttonLabel: String,
    form: CardForm,
    isSaving: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbars: LabSnackbars = rememberLabSnackbars(),
) {
    val p = LabTheme.palette
    val mono = LabText.mono(p, size = 15f)
    val done = KeyboardOptions(imeAction = ImeAction.Done)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = { LabTopBar(title, onBack = onBack) },
        snackbarHost = { LabSnackbarHost(snackbars, Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(16.dp)
                .testTag("card_form"),
        ) {
            LabSectionLabel(stringResource(R.string.card_id_label))
            Spacer(Modifier.height(8.dp))
            LabTextField(
                state = form.id,
                style = mono,
                hint = stringResource(R.string.card_id_placeholder),
                leadingIcon = painterResource(R.drawable.ic_credit_card),
                error = form.idError?.let { stringResource(it) },
                keyboardOptions = done.copy(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_id"),
            )

            Spacer(Modifier.height(20.dp))
            LabSectionLabel(stringResource(R.string.card_name_label))
            Spacer(Modifier.height(8.dp))
            LabTextField(
                state = form.name,
                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                hint = stringResource(R.string.card_name_placeholder),
                leadingIcon = painterResource(R.drawable.ic_label),
                error = form.nameError?.let { stringResource(it) },
                keyboardOptions = done,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_name"),
            )

            Spacer(Modifier.height(20.dp))
            LabSectionLabel(stringResource(R.string.card_prefix_label))
            Spacer(Modifier.height(8.dp))
            LabTextField(
                state = form.prefix,
                style = mono,
                hint = stringResource(R.string.card_prefix_placeholder),
                keyboardOptions = done,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_prefix"),
            )

            Spacer(Modifier.height(20.dp))
            LabSectionLabel(stringResource(R.string.card_suffix_label))
            Spacer(Modifier.height(8.dp))
            LabTextField(
                state = form.suffix,
                style = mono,
                hint = stringResource(R.string.card_suffix_placeholder),
                keyboardOptions = done,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_suffix"),
            )

            Spacer(Modifier.height(32.dp))
            LabFilledButton(
                onClick = onSubmit,
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_submit"),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = p.onFill, strokeWidth = 2.dp)
                } else {
                    Text(buttonLabel)
                }
            }
        }
    }
}

/** The edit screen when its card is not among the saved ones */
@Composable
fun CardMissingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = { LabTopBar(stringResource(R.string.edit_card_title), onBack = onBack) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.no_cards), style = MaterialTheme.typography.bodyMedium.copy(color = p.ink))
        }
    }
}

@Preview(name = "Light", widthDp = 411, heightDp = 914)
@Composable
private fun CardFormLightPreview() {
    CaliGoTheme(darkTheme = false) {
        CardFormScreen("Create New Card", "Create Card", CardForm.forNewCard(), isSaving = false, onSubmit = {}, onBack = {})
    }
}

@Preview(name = "Dark", widthDp = 411, heightDp = 914)
@Composable
private fun CardFormDarkPreview() {
    CaliGoTheme(darkTheme = true) {
        CardFormScreen("Edit Card", "Save Changes", CardForm("06051868", "Mía", "19", "981"), isSaving = true, onSubmit = {}, onBack = {})
    }
}
