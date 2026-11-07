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
    /** This palette [t] of the way to [other], as the Flutter extension's lerp */
    fun lerp(other: LabPalette, t: Float): LabPalette = when (t) {
        0f -> this
        1f -> other
        else -> LabPalette(
            bg = lerpChannels(bg, other.bg, t),
            paper = lerpChannels(paper, other.paper, t),
            paper2 = lerpChannels(paper2, other.paper2, t),
            rule = lerpChannels(rule, other.rule, t),
            ruleSoft = lerpChannels(ruleSoft, other.ruleSoft, t),
            ink = lerpChannels(ink, other.ink, t),
            muted = lerpChannels(muted, other.muted, t),
            accent = lerpChannels(accent, other.accent, t),
            fill = lerpChannels(fill, other.fill, t),
            onFill = lerpChannels(onFill, other.onFill, t),
            ok = lerpChannels(ok, other.ok, t),
            warn = lerpChannels(warn, other.warn, t),
            crit = lerpChannels(crit, other.crit, t),
        )
    }

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

/**
 * [a] [t] of the way to [b], each sRGB channel apart, as Flutter's
 * Color.lerp mixes; Compose's own lerp goes through Oklab
 */
fun lerpChannels(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
