package com.example.snapfindai.ui.theme

import androidx.compose.ui.graphics.Color

// "Clay Wave" -- the app's one committed visual identity (see the look-book
// mockup this was picked from): Soft Clay's warm putty palette and rounded
// Fredoka/Karla type, carried by Material 3's real "expressive" components
// (CircularWavyProgressIndicator, standard elevation) rather than a custom
// neumorphic shadow system. Everything here maps onto Material 3's
// ColorScheme roles; there's no dynamic/Material-You branch, by design --
// this palette is the app's identity regardless of the user's wallpaper.

// ---- Light ----
val ClayBackgroundLight = Color(0xFFEAE7E1)
val ClaySurfaceLight = Color(0xFFEFECE6)
val ClaySurfaceVariantLight = Color(0xFFE3E0D9)
val ClayInkLight = Color(0xFF3A362F)
val ClayInkSoftLight = Color(0xFF847F72)
val ClayAccentLight = Color(0xFFE8785A)
val ClayAccentInkLight = Color(0xFFFFF6F2)
/** The mockup's "tertiary" role -- a sage green used for saved/selected badges, kept separate from the coral accent so "this succeeded" reads differently from "tap this to act." */
val ClayTertiaryLight = Color(0xFF7C9885)
val ClayTertiaryInkLight = Color(0xFFFFFFFF)
val ClayOutlineLight = Color(0xFFD8D3C7)
val ClayErrorLight = Color(0xFFBA3B26)

// ---- Dark ----
val ClayBackgroundDark = Color(0xFF211E18)
val ClaySurfaceDark = Color(0xFF2A2620)
val ClaySurfaceVariantDark = Color(0xFF302B23)
val ClayInkDark = Color(0xFFEEE8DB)
val ClayInkSoftDark = Color(0xFFA8A08E)
val ClayAccentDark = Color(0xFFF08863)
val ClayAccentInkDark = Color(0xFF241109)
val ClayTertiaryDark = Color(0xFF93AD9C)
val ClayTertiaryInkDark = Color(0xFF0E1712)
val ClayOutlineDark = Color(0xFF3A352C)
val ClayErrorDark = Color(0xFFFF8A70)
