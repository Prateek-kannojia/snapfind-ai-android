package com.example.snapfindai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ClayLightColorScheme = lightColorScheme(
    primary = ClayAccentLight,
    onPrimary = ClayAccentInkLight,
    tertiary = ClayTertiaryLight,
    onTertiary = ClayTertiaryInkLight,
    background = ClayBackgroundLight,
    onBackground = ClayInkLight,
    surface = ClaySurfaceLight,
    onSurface = ClayInkLight,
    surfaceVariant = ClaySurfaceVariantLight,
    onSurfaceVariant = ClayInkSoftLight,
    outline = ClayOutlineLight,
    error = ClayErrorLight,
    onError = ClayAccentInkLight,
)

private val ClayDarkColorScheme = darkColorScheme(
    primary = ClayAccentDark,
    onPrimary = ClayAccentInkDark,
    tertiary = ClayTertiaryDark,
    onTertiary = ClayTertiaryInkDark,
    background = ClayBackgroundDark,
    onBackground = ClayInkDark,
    surface = ClaySurfaceDark,
    onSurface = ClayInkDark,
    surfaceVariant = ClaySurfaceVariantDark,
    onSurfaceVariant = ClayInkSoftDark,
    outline = ClayOutlineDark,
    error = ClayErrorDark,
    onError = ClayAccentInkDark,
)

/**
 * Clay Wave -- the app's one committed identity, light and dark variants
 * both designed for it specifically (see Color.kt). Deliberately no
 * Material You / dynamic-color branch: the palette is the app's identity
 * regardless of the user's wallpaper, not a fallback for when one isn't
 * available.
 */
@Composable
fun SnapFindAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ClayDarkColorScheme else ClayLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ClayShapes,
        content = content
    )
}
