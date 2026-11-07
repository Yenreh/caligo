package com.yenreh.caligo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.R

/**
 * Bundled families: Fraunces for headings, Plex Sans for text and Plex Mono
 * for numbers. Bundled so the first launch needs no network.
 */
object AppFonts {
    val serif = FontFamily(
        Font(R.font.fraunces_bold, FontWeight.Bold),
        Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )
}

/** Text styles the Material type scale has no slot for */
object LabText {
    /** Uppercase, letter-spaced label that opens a section */
    fun sectionLabel(p: LabPalette) = TextStyle(
        fontFamily = AppFonts.sans,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = p.muted,
    )

    /** Italic serif line under a heading, also used for status */
    fun statusLine(p: LabPalette) = TextStyle(
        fontFamily = AppFonts.serif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 1.35.em,
        color = p.muted,
    )

    fun mono(p: LabPalette, size: Float = 14f, color: Color = p.ink) = TextStyle(
        fontFamily = AppFonts.mono,
        fontWeight = FontWeight.Medium,
        fontSize = size.sp,
        color = color,
    )
}

/**
 * The Material 3 scale in Plex Sans, as Flutter's 2021 typography gives it,
 * with Fraunces bold for display, headline and titleLarge
 */
fun labTypography(p: LabPalette): Typography {
    val base = Typography()

    fun TextStyle.sans() = copy(fontFamily = AppFonts.sans)

    fun TextStyle.serif(size: Float) = copy(
        fontFamily = AppFonts.serif,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        lineHeight = 1.15.em,
        letterSpacing = 0.sp,
    )

    return Typography(
        displayLarge = base.displayLarge.serif(48f),
        displayMedium = base.displayMedium.serif(40f),
        displaySmall = base.displaySmall.serif(34f),
        headlineLarge = base.headlineLarge.serif(30f),
        headlineMedium = base.headlineMedium.serif(26f),
        headlineSmall = base.headlineSmall.serif(22f),
        titleLarge = base.titleLarge.serif(20f),
        titleMedium = base.titleMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.sans().copy(lineHeight = 1.45.em),
        bodyMedium = base.bodyMedium.sans().copy(lineHeight = 1.45.em),
        bodySmall = base.bodySmall.sans().copy(color = p.muted),
        labelLarge = base.labelLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.sans().copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.sans(),
    )
}
