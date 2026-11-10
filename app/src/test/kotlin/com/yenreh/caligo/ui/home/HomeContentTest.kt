package com.yenreh.caligo.ui.home

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.ui.cards.CardsState
import com.yenreh.caligo.ui.common.setContentAt
import com.yenreh.caligo.ui.stops.StopsState
import com.yenreh.caligo.ui.theme.CaliGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the home lists, as the settings choose, at Pixel size */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class HomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: HomeContent, cards: CardsState = CardsState(), stops: StopsState? = StopsState()) {
        compose.setContentAt(HomeSamples.NOW) { CaliGoTheme { HomeScreen(HomeSamples.settings(content), cards, stops) } }
    }

    @Test
    fun stopsOnlyHomeAsksForStops() {
        show(HomeContent.STOPS)
        compose.onNodeWithText("No saved stops").assertIsDisplayed()
    }

    @Test
    fun bothShowTheCardsHint() {
        show(HomeContent.BOTH)
        compose.onNodeWithText("No cards").assertIsDisplayed()
    }

    @Test
    fun cardsLoadingShowProgressInsteadOfTheHint() {
        show(HomeContent.CARDS, cards = CardsState(isLoading = true))
        compose.onAllNodesWithTag("home_empty").assertCountEquals(0)
    }

    @Test
    fun stopsLoadingShowProgressInsteadOfTheHint() {
        show(HomeContent.STOPS, stops = StopsState(isLoading = true))
        compose.onAllNodesWithTag("home_empty").assertCountEquals(0)
        compose.onAllNodesWithText("No saved stops").assertCountEquals(0)
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test
    fun noCardsWithTheStopsStillLoadingShowProgressToo() {
        show(HomeContent.BOTH, stops = StopsState(isLoading = true))
        compose.onAllNodesWithTag("home_empty").assertCountEquals(0)
    }

    @Test
    fun cardsOnlyListsTheCardsInFull() {
        show(HomeContent.CARDS, HomeSamples.cardsState, stops = null)
        compose.onNodeWithText("My cards", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("1906 0518 6898 1").assertIsDisplayed()
        compose.onNodeWithText("15.300 $").assertIsDisplayed()
        compose.onNodeWithText("4 FARES (+1)").assertIsDisplayed()
        compose.onAllNodesWithTag("favorite_stop").assertCountEquals(0)
    }

    @Test
    fun oneMoreFareOwingIsMarkedAndReadOut() {
        show(HomeContent.CARDS, HomeSamples.cardsState, stops = null)
        // 15.300 at 3.500: four fares and 1.300 left, enough to board once more owing
        compose.onNodeWithContentDescription("4 fares, and 1 more on credit").assertIsDisplayed()
        // 2.400, short of one fare: that one alone
        compose.onNodeWithText("0 FARES (+1)").assertExists()
        compose.onNodeWithContentDescription("1 fare on credit").assertExists()
    }

    @Test
    fun nothingLeftAfterTheWholeFaresMarksNoMore() {
        val card = HomeSamples.cards.first().copy(balance = 7000.0)
        show(HomeContent.CARDS, CardsState(cards = listOf(card)), stops = null)
        compose.onNodeWithText("2 FARES").assertIsDisplayed()
        compose.onAllNodesWithText("(+1)", substring = true).assertCountEquals(0)
    }

    @Test
    @Config(fontScale = 2f)
    fun largeTextPutsTheOneOwingBelowInTheCondensedCards() {
        show(HomeContent.BOTH, HomeSamples.cardsState, HomeSamples.stopsState)
        // The balance's column grows no wider, so the card number keeps its two lines
        compose.onNodeWithText("4 fares\n(+1)").assertIsDisplayed()
    }

    @Test
    fun noFaresAtAllWhenOwingAlready() {
        val card = HomeSamples.cards.first().copy(balance = -1200.0)
        show(HomeContent.CARDS, CardsState(cards = listOf(card)), stops = null)
        compose.onAllNodesWithText("FARE", substring = true).assertCountEquals(0)
    }

    @Test
    fun bothCondenseTheCardsAndListTheStopsWithTheirBuses() {
        show(HomeContent.BOTH, HomeSamples.cardsState, HomeSamples.stopsState)
        // Condensed: the fares without the chip's capitals
        compose.onNodeWithText("4 fares (+1)").assertIsDisplayed()
        compose.onNodeWithText("Trabajo").assertIsDisplayed()
        compose.onNodeWithText("Plaza de Cayzedo A1").assertIsDisplayed()
        compose.onNodeWithText("E21 · T31").assertIsDisplayed()
        // Only the lines followed
        compose.onAllNodesWithText("Valle Grande").assertCountEquals(0)
        compose.onNodeWithText("2 min").assertIsDisplayed()
    }

    @Test
    fun aStopTheHomeLeavesOutIsNotListed() {
        val stops = HomeSamples.stopsState.let { s ->
            s.copy(favorites = s.favorites.map { if (it.id == "500800") it.copy(showOnHome = false) else it })
        }
        show(HomeContent.STOPS, stops = stops)
        compose.onAllNodesWithText("Trabajo").assertCountEquals(0)
    }

    @Test
    fun theButtonsLeadWhereTheirActionsSay() {
        val opened = mutableListOf<String>()
        compose.setContentAt(HomeSamples.NOW) {
            CaliGoTheme {
                HomeScreen(
                    HomeSamples.settings(HomeContent.BOTH),
                    HomeSamples.cardsState,
                    HomeSamples.stopsState,
                    actions = HomeActions(
                        onSettings = { opened.add("settings") },
                        onCards = { opened.add("cards") },
                        onSearch = { opened.add("search") },
                        onPlan = { opened.add("plan") },
                        onStops = { opened.add("stops") },
                    ),
                )
            }
        }
        for (name in listOf("Settings", "Cards", "Find stops", "Plan a trip", "Favorites")) {
            compose.onNodeWithContentDescription(name).performClick()
        }
        assertEquals(listOf("settings", "cards", "search", "plan", "stops"), opened)
    }
}
