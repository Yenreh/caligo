package com.yenreh.caligo.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.LineBus
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.domain.entities.TransitLine
import com.yenreh.caligo.ui.FakeLocationSource
import com.yenreh.caligo.ui.FakeStopsRepository
import com.yenreh.caligo.ui.lines.LineViewModel
import com.yenreh.caligo.ui.lines.LinesViewModel
import com.yenreh.caligo.ui.map.FixtureTiles
import com.yenreh.caligo.ui.stops.StopsViewModel
import com.yenreh.caligo.ui.theme.CaliGoTheme
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The line screen as the app opens it, from the Lines tab of the add
 * screen, over a fake repository: the activity's stops and lines put
 * there first, the line screen's own made over the same repository
 */
@RunWith(AndroidJUnit4::class)
class LinesNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<CaliGoApp>().container

    private val repository = FakeStopsRepository(emptyList(), emptyList()).apply {
        transitLines = listOf(TransitLine(347, "A47"), TransitLine(131, "T31"))
        routes = mapOf(
            "T31" to listOf(
                LineStop("1", "Chiminangos", 3.48, -76.49, 0, 1),
                LineStop("2", "Universidades", 3.37, -76.53, 0, 2),
            ),
        )
        buses = mapOf("T31" to listOf(LineBus("9", 3.42, -76.51, direction = 0)))
    }

    private val lineModels = mutableListOf<LineViewModel>()

    private fun show() {
        container.settings.setHomeContent(HomeContent.STOPS)
        ViewModelProvider.create(
            compose.activity,
            viewModelFactory {
                initializer {
                    StopsViewModel(repository, location = FakeLocationSource(), settings = container.settings.settings)
                }
            },
        )[StopsViewModel::class]
        ViewModelProvider.create(
            compose.activity,
            viewModelFactory { initializer { LinesViewModel(repository) } },
        )[LinesViewModel::class]
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            CaliGoTheme {
                AppNavigation(
                    settings,
                    mapTiles = { remember { FixtureTiles() } },
                    lineViewModel = { line ->
                        viewModel<LineViewModel>(factory = viewModelFactory { initializer { LineViewModel(line, repository) } })
                            .also { if (it !in lineModels) lineModels.add(it) }
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { exists(matcher) }

    private fun waitGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_TIMEOUT_MS) { !exists(matcher) }

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun aLineOfTheLinesTabOpensItsRouteAndBackComesToTheList() {
        show()
        compose.onNodeWithContentDescription("Find stops").performClick()
        waitFor(hasTestTag("tab_lines"))
        compose.onNodeWithTag("tab_lines").performClick()
        waitFor(hasText("T31"))

        compose.onNodeWithText("T31").performClick()

        waitFor(hasText("Universidades"))
        waitFor(hasTestTag("bus_9"))
        compose.onNodeWithText("Chiminangos").assertExists()
        assertEquals(listOf("T31"), repository.routesAsked)
        assertEquals("T31", lineModels.single().line)

        systemBack()
        waitGone(hasText("Chiminangos"))
        waitFor(hasText("A47"))
        compose.onNodeWithText("Find stops").assertExists()
    }

    @Test
    fun theLineScreensArrowComesBackAndLetsItsBusesGo() {
        show()
        compose.onNodeWithContentDescription("Find stops").performClick()
        waitFor(hasTestTag("tab_lines"))
        compose.onNodeWithTag("tab_lines").performClick()
        waitFor(hasText("T31"))
        compose.onNodeWithText("T31").performClick()
        waitFor(hasTestTag("bus_9"))
        val asked = repository.busesAsked.size

        compose.onNodeWithContentDescription("Back").performClick()

        waitGone(hasText("Chiminangos"))
        waitFor(hasText("A47"))
        // The screen went with its view model: no round is left asking
        compose.waitUntil(WAIT_TIMEOUT_MS) { !lineModels.single().viewModelScope.isActive }
        assertEquals(asked, repository.busesAsked.size)
        assertFalse(exists(hasContentDescription("Update bus positions")))
    }
}
