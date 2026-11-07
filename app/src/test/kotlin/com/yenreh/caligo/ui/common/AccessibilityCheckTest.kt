package com.yenreh.caligo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.assertControlsAccessible
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The check the screens go through finds what it is for */
@RunWith(AndroidJUnit4::class)
class AccessibilityCheckTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aControlWithNoNameFails() {
        compose.setContent { Box(Modifier.size(48.dp).clickable {}) }

        val error = assertThrows(AssertionError::class.java) { compose.assertControlsAccessible() }
        assertTrue(error.message.orEmpty(), "unnamed control" in error.message.orEmpty())
    }

    @Test
    fun aNamedControlPasses() {
        compose.setContent { Box(Modifier.size(36.dp).semantics { contentDescription = "Retry" }.clickable {}) }

        // Compose widens a small target to 48 dp, as a Flutter IconButton is
        compose.assertControlsAccessible()
    }
}
