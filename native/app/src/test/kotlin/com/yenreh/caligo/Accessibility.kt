package com.yenreh.caligo

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/**
 * Every control on screen can be told and hit: each node that takes a
 * tap says what it is, in words or a content description, as Flutter's
 * tooltips name its icon buttons, and its touch target is 48 dp at least
 * each way, the size of Flutter's IconButton and Android's guideline.
 */
fun ComposeTestRule.assertControlsAccessible() {
    waitForIdle()
    // Those scrolled out of sight have no bounds to measure
    val controls = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        .filter { !it.boundsInRoot.isEmpty }
    assertTrue("No controls found", controls.isNotEmpty())
    val minimum = density.run { 48.dp.toPx() } - 0.5f
    val problems = controls.mapNotNull { node ->
        val name = node.name()
        val touch = node.touchBoundsInRoot
        when {
            name.isBlank() -> "unnamed control at ${node.boundsInRoot}"
            touch.width < minimum || touch.height < minimum ->
                "\"$name\" touch target ${density.run { touch.width.toDp() }} x ${density.run { touch.height.toDp() }}"
            else -> null
        }
    }
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
}

/** What a screen reader reads for [this]: its text and descriptions, its children's merged in */
private fun SemanticsNode.name(): String {
    val config = config
    return listOfNotNull(
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
        config.getOrNull(SemanticsProperties.EditableText)?.text,
        config.getOrNull(SemanticsActions.OnClick)?.label,
    ).joinToString(" ").trim()
}
