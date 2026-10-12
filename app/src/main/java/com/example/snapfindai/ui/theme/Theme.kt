package com.example.snapfindai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Every role Material 3 defines is mapped here, on purpose.
//
// Anything left out does not fall back to "something sensible from the rest
// of the scheme" -- it falls back to Material's own baseline palette, which
// is lavender. That is not theoretical: this scheme used to map 11 roles, so
// AlertDialog (which paints `surfaceContainerHigh`) rendered the delete
// confirmation as a #ECE6F0 lavender panel in the middle of a clay app, and
// Card (`surfaceContainerLow`) sat on #F7F2FA. The leak is invisible until
// you happen to open the one component that uses the one role you skipped,
// which is why the fix is "map all of them" rather than "map the ones we
// currently use".
//
// A quick way to catch a regression here: temporarily set the roles you
// think are unused to magenta, run the app, and open every dialog, menu and
// snackbar. Anything that flashes pink is a role still coming from baseline.

private val ClayLightColorScheme = lightColorScheme(
    primary = ClayAccentLight,
    onPrimary = ClayAccentInkOnFillLight,
    primaryContainer = ClayAccentContainerLight,
    onPrimaryContainer = ClayAccentInkLight,

    // Secondary carries the "quiet but still interactive" weight: tonal
    // buttons, the progress track, chips. It is a warm neutral rather than a
    // second brand hue -- Clay Wave has one accent, and inventing a second
    // one here would dilute it.
    secondary = ClayInkSoftLight,
    onSecondary = ClaySurfaceLight,
    secondaryContainer = ClaySecondaryContainerLight,
    onSecondaryContainer = ClayOnSecondaryContainerLight,

    tertiary = ClayTertiaryLight,
    onTertiary = ClayTertiaryInkLight,
    tertiaryContainer = ClayTertiaryContainerLight,
    onTertiaryContainer = ClayOnTertiaryContainerLight,

    background = ClayBackgroundLight,
    onBackground = ClayInkLight,
    surface = ClaySurfaceLight,
    onSurface = ClayInkLight,
    surfaceVariant = ClaySurfaceVariantLight,
    onSurfaceVariant = ClayInkSoftLight,

    surfaceContainerLowest = ClaySurfaceContainerLowestLight,
    surfaceContainerLow = ClaySurfaceContainerLowLight,
    surfaceContainer = ClaySurfaceContainerLight,
    surfaceContainerHigh = ClaySurfaceContainerHighLight,
    surfaceContainerHighest = ClaySurfaceContainerHighestLight,
    surfaceDim = ClaySurfaceDimLight,
    surfaceBright = ClaySurfaceBrightLight,

    // Soft Clay separates surfaces with shadow, not with a tinted overlay.
    // Material's tonal elevation blends `surfaceTint` into a raised surface
    // in proportion to its elevation, and the default tint is `primary` --
    // which would push a faint coral into every Card and Surface that sets a
    // tonalElevation. Pinning the tint to the surface colour makes that blend
    // a no-op and keeps raised clay the same colour as flat clay.
    surfaceTint = ClaySurfaceLight,

    outline = ClayOutlineLight,
    outlineVariant = ClayOutlineVariantLight,

    error = ClayErrorLight,
    onError = ClayErrorInkLight,
    errorContainer = ClayErrorContainerLight,
    onErrorContainer = ClayOnErrorContainerLight,

    inverseSurface = ClayInverseSurfaceLight,
    inverseOnSurface = ClayInverseOnSurfaceLight,
    inversePrimary = ClayInversePrimaryLight,
)

private val ClayDarkColorScheme = darkColorScheme(
    primary = ClayAccentDark,
    onPrimary = ClayAccentInkOnFillDark,
    primaryContainer = ClayAccentContainerDark,
    onPrimaryContainer = ClayAccentInkDark,

    secondary = ClayInkSoftDark,
    onSecondary = ClaySurfaceDark,
    secondaryContainer = ClaySecondaryContainerDark,
    onSecondaryContainer = ClayOnSecondaryContainerDark,

    tertiary = ClayTertiaryDark,
    onTertiary = ClayTertiaryInkDark,
    tertiaryContainer = ClayTertiaryContainerDark,
    onTertiaryContainer = ClayOnTertiaryContainerDark,

    background = ClayBackgroundDark,
    onBackground = ClayInkDark,
    surface = ClaySurfaceDark,
    onSurface = ClayInkDark,
    surfaceVariant = ClaySurfaceVariantDark,
    onSurfaceVariant = ClayInkSoftDark,

    surfaceContainerLowest = ClaySurfaceContainerLowestDark,
    surfaceContainerLow = ClaySurfaceContainerLowDark,
    surfaceContainer = ClaySurfaceContainerDark,
    surfaceContainerHigh = ClaySurfaceContainerHighDark,
    surfaceContainerHighest = ClaySurfaceContainerHighestDark,
    surfaceDim = ClaySurfaceDimDark,
    surfaceBright = ClaySurfaceBrightDark,

    surfaceTint = ClaySurfaceDark,

    outline = ClayOutlineDark,
    outlineVariant = ClayOutlineVariantDark,

    error = ClayErrorDark,
    onError = ClayErrorInkDark,
    errorContainer = ClayErrorContainerDark,
    onErrorContainer = ClayOnErrorContainerDark,

    inverseSurface = ClayInverseSurfaceDark,
    inverseOnSurface = ClayInverseOnSurfaceDark,
    inversePrimary = ClayInversePrimaryDark,
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