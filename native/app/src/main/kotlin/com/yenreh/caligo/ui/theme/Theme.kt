package com.yenreh.caligo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Accent carries highlights (links, focus, progress); the deeper fill is
 * reserved for the one primary action and reaches components through
 * primaryContainer.
 */
fun labColorScheme(p: LabPalette, dark: Boolean): ColorScheme {
    val onAccent = if (dark) p.bg else p.onFill
    // Unset Flutter slots fall back to their base colour: the containers
    // and fixed colours of secondary and tertiary to the colour itself
    return ColorScheme(
        primary = p.accent,
        onPrimary = onAccent,
        primaryContainer = p.fill,
        onPrimaryContainer = p.onFill,
        inversePrimary = p.fill,
        secondary = p.accent,
        onSecondary = onAccent,
        secondaryContainer = p.paper2,
        onSecondaryContainer = p.ink,
        tertiary = p.ok,
        onTertiary = p.bg,
        tertiaryContainer = p.ok,
        onTertiaryContainer = p.bg,
        background = p.bg,
        onBackground = p.ink,
        surface = p.paper,
        onSurface = p.ink,
        surfaceVariant = p.paper2,
        onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent,
        inverseSurface = p.ink,
        inverseOnSurface = p.bg,
        error = p.crit,
        onError = onAccent,
        errorContainer = p.paper2,
        onErrorContainer = p.crit,
        outline = p.rule,
        outlineVariant = p.ruleSoft,
        scrim = Color(0x8A000000), // Flutter's black54
        surfaceBright = p.paper2,
        surfaceDim = p.bg,
        surfaceContainer = p.paper,
        surfaceContainerHigh = p.paper2,
        surfaceContainerHighest = p.paper2,
        surfaceContainerLow = p.paper,
        surfaceContainerLowest = p.bg,
        primaryFixed = p.accent,
        primaryFixedDim = p.accent,
        onPrimaryFixed = onAccent,
        onPrimaryFixedVariant = onAccent,
        secondaryFixed = p.accent,
        secondaryFixedDim = p.accent,
        onSecondaryFixed = onAccent,
        onSecondaryFixedVariant = onAccent,
        tertiaryFixed = p.ok,
        tertiaryFixedDim = p.ok,
        onTertiaryFixed = p.bg,
        onTertiaryFixedVariant = p.bg,
    )
}

/** Square corners everywhere, as the lab style draws them */
private val square: CornerBasedShape = RoundedCornerShape(0)
private val labShapes = Shapes(
    extraSmall = square,
    small = square,
    medium = square,
    large = square,
    extraLarge = square,
)

/** Light or dark following the system */
@Composable
fun CaliGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) LabPalette.Dark else LabPalette.Light
    MaterialTheme(
        colorScheme = labColorScheme(palette, darkTheme),
        typography = labTypography(palette),
        shapes = labShapes,
    ) {
        // Ink by default, as Flutter's text and icon themes give it
        CompositionLocalProvider(
            LocalLabPalette provides palette,
            LocalContentColor provides palette.ink,
            content = content,
        )
    }
}

/** The palette of the current theme */
object LabTheme {
    val palette: LabPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalLabPalette.current
}
