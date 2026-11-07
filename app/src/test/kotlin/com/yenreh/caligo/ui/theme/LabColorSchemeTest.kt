package com.yenreh.caligo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** The scheme maps the palette as app_theme.dart does */
class LabColorSchemeTest {
    @Test
    fun lightScheme() {
        val p = LabPalette.Light
        val scheme = labColorScheme(p, dark = false)
        assertEquals(Color(0xFF5B21B6), scheme.primary)
        assertEquals(p.onFill, scheme.onPrimary)
        assertEquals(Color(0xFF3F1D7A), scheme.primaryContainer)
        assertEquals(p.paper, scheme.surface)
        assertEquals(p.bg, scheme.background)
        assertEquals(p.ink, scheme.onSurface)
        assertEquals(p.rule, scheme.outline)
        assertEquals(p.crit, scheme.error)
        assertEquals(Color.Transparent, scheme.surfaceTint)
    }

    @Test
    fun darkSchemePutsBgOnTheAccent() {
        val p = LabPalette.Dark
        val scheme = labColorScheme(p, dark = true)
        assertEquals(Color(0xFFC4A8FF), scheme.primary)
        assertEquals(p.bg, scheme.onPrimary)
        assertEquals(p.bg, scheme.onError)
        assertEquals(Color(0xFF15121A), scheme.surfaceContainerLowest)
    }
}
