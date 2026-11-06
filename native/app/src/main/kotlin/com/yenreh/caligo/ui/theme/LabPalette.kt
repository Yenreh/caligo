package com.yenreh.caligo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the "lab report" style shared with NetSpeedDiag and the
 * browser extensions: ink on paper, rules instead of shadows.
 */
@Immutable
data class LabPalette(
    val bg: Color,
    val paper: Color,
    val paper2: Color,
    val rule: Color,
    val ruleSoft: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val fill: Color,
    val onFill: Color,
    val ok: Color,
    val warn: Color,
    val crit: Color,
) {
    companion object {
        val Dark = LabPalette(
            bg = Color(0xFF15121A),
            paper = Color(0xFF1C1823),
            paper2 = Color(0xFF231E2B),
            rule = Color(0xFF3D3548),
            ruleSoft = Color(0xFF2D2736),
            ink = Color(0xFFEFE7DA),
            muted = Color(0xFFA59CAE),
            accent = Color(0xFFC4A8FF),
            fill = Color(0xFF4A2391),
            onFill = Color(0xFFF3ECDF),
            ok = Color(0xFF9CC794),
            warn = Color(0xFFE6B45E),
            crit = Color(0xFFF08A78),
        )

        val Light = LabPalette(
            bg = Color(0xFFF7F3EA),
            paper = Color(0xFFFBF8F1),
            paper2 = Color(0xFFF0E9DC),
            rule = Color(0xFFCFC6B6),
            ruleSoft = Color(0xFFE2DACB),
            ink = Color(0xFF1C1917),
            muted = Color(0xFF736B64),
            accent = Color(0xFF5B21B6),
            fill = Color(0xFF3F1D7A),
            onFill = Color(0xFFF7F3EA),
            ok = Color(0xFF3F7A3A),
            warn = Color(0xFF9A5B06),
            crit = Color(0xFFB3261E),
        )
    }
}

val LocalLabPalette = staticCompositionLocalOf { LabPalette.Light }
