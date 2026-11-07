package com.yenreh.caligo.ui.cards

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.captureWindow
import com.yenreh.caligo.domain.entities.Settings
import com.yenreh.caligo.ui.home.HomeSamples
import com.yenreh.caligo.ui.lab.LabTone
import com.yenreh.caligo.ui.lab.rememberLabSnackbars
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cards screens at Pixel size (1080 x 2400 px, 420 dpi), in Spanish,
 * light and dark: the list and its empty state, the create form as it
 * opens and after a submit with nothing in it, and the edit form
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-" + RobolectricDeviceQualifiers.Pixel7)
class CardsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun cards(name: String, state: CardsState) {
        compose.setContent { CaliGoTheme { CardsScreen(state, farePrice = Settings.DEFAULT_FARE_PRICE) } }
        compose.captureWindow(name)
    }

    @Test
    fun cardsLight() = cards("cards_light", HomeSamples.cardsState)

    @Test
    @Config(qualifiers = "+night")
    fun cardsDark() = cards("cards_dark", HomeSamples.cardsState)

    /** The snackbar above the add button, as Flutter's Scaffold floats it */
    @Test
    fun cardsSnackbarLight() {
        compose.setContent {
            val snackbars = rememberLabSnackbars()
            CaliGoTheme { CardsScreen(HomeSamples.cardsState, farePrice = Settings.DEFAULT_FARE_PRICE, snackbars = snackbars) }
            LaunchedEffect(Unit) { snackbars.show("Saldo actualizado", LabTone.OK) }
        }
        compose.captureWindow("cards_snackbar_light")
    }

    @Test
    fun cardsEmptyLight() = cards("cards_empty_light", CardsState())

    @Test
    @Config(qualifiers = "+night")
    fun cardsEmptyDark() = cards("cards_empty_dark", CardsState())

    private fun form(name: String, form: CardForm, editing: Boolean = false) {
        compose.setContent {
            CaliGoTheme {
                CardFormScreen(
                    title = if (editing) "Editar Tarjeta" else "Crear Nueva Tarjeta",
                    buttonLabel = if (editing) "Guardar Cambios" else "Crear Tarjeta",
                    form = form,
                    isSaving = false,
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
        compose.captureWindow(name)
    }

    private fun invalid() = CardForm.forNewCard().apply { validate() }

    private fun mine() = CardForm().apply { load(HomeSamples.cards.first()) }

    @Test
    fun createLight() = form("create_card_light", CardForm.forNewCard())

    @Test
    @Config(qualifiers = "+night")
    fun createDark() = form("create_card_dark", CardForm.forNewCard())

    @Test
    fun createErrorLight() = form("create_card_error_light", invalid())

    @Test
    @Config(qualifiers = "+night")
    fun createErrorDark() = form("create_card_error_dark", invalid())

    @Test
    fun editLight() = form("edit_card_light", mine(), editing = true)

    @Test
    @Config(qualifiers = "+night")
    fun editDark() = form("edit_card_dark", mine(), editing = true)
}
