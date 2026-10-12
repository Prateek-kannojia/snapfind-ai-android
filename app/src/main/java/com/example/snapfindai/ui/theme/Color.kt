package com.example.snapfindai.ui.theme

import androidx.compose.ui.graphics.Color

// "Clay Wave" -- the app's one committed visual identity (see the look-book
// mockup this was picked from): Soft Clay's warm putty palette and rounded
// Fredoka/Karla type, carried by Material 3's real "expressive" components
// (CircularWavyProgressIndicator, standard elevation) rather than a custom
// neumorphic shadow system. Everything here maps onto Material 3's
// ColorScheme roles; there's no dynamic/Material-You branch, by design --
// this palette is the app's identity regardless of the user's wallpaper.
//
// ---------------------------------------------------------------------------
// Every pairing below is contrast-checked against WCAG 2.1: 4.5:1 for body
// text, 3:1 for icons and large text. The measured ratio is written next to
// each `on*` colour. If you change a value here, re-check its partner --
// several of these are within 0.1 of the threshold and a "slightly nicer"
// hex can silently drop the app below AA.
//
// Two rules this palette exists to enforce, both learned the hard way:
//
//   1. A FILL colour and a TEXT colour cannot be the same hex in a light
//      theme. ClayAccent is gorgeous as a button fill and illegible as ink
//      (2.34:1 on the page). ClayAccentInk is the same hue taken down in
//      value, and it is what accent *text* and accent *icons* must use.
//
//   2. A control whose border is the only thing identifying it needs 3:1
//      for that border (WCAG 1.4.11). `outline` here is deliberately softer
//      than that -- it is for dividers and decorative edges. An outlined
//      button must draw its border in ClayAccentInk, not in outline.
// ---------------------------------------------------------------------------

// ---- Light ----
val ClayBackgroundLight = Color(0xFFEAE7E1)
val ClaySurfaceLight = Color(0xFFEFECE6)
val ClaySurfaceVariantLight = Color(0xFFE3E0D9)
val ClayInkLight = Color(0xFF3A362F)
/** Secondary text. 4.84:1 on background, 5.07:1 on surface, 4.53:1 on surfaceVariant -- it clears AA on all three light grounds, which the previous #847F72 did on none of them (3.24:1). */
val ClayInkSoftLight = Color(0xFF686358)
/** The look-book coral. A FILL only -- see rule 1 above. */
val ClayAccentLight = Color(0xFFE8785A)
/** Ink on the coral fill. 5.68:1. The light theme used a near-white here and measured 2.71:1; the dark theme already inverted it correctly, and this brings light into line. */
val ClayAccentInkOnFillLight = Color(0xFF2B1C14)
/** A soft coral wash for accent containers (the pause/resume FAB). */
val ClayAccentContainerLight = Color(0xFFF7D9CE)
/** Coral as TEXT and ICONS. 4.60:1 on background, 4.82:1 on surface. On ClayAccentContainer it is 4.27:1 -- icon-grade (clears 3:1), not body-text grade, so don't put running text on that pairing. */
val ClayAccentInkLight = Color(0xFFB04423)
/** The tonal-button / progress-track ground. 1.34:1 against the page, so a tonal button is finally a visible shape rather than a 1.07:1 ghost held up by its shadow. */
val ClaySecondaryContainerLight = Color(0xFFD0C9BA)
val ClayOnSecondaryContainerLight = Color(0xFF2E2B25)
/** Sage, for "this succeeded" -- deliberately not the coral accent, so a saved photo doesn't look like a tappable one. NOTE: currently unreferenced outside this file; the saved badge still tints with `primary`, which is why a saved tick and a selected tick are presently the same mark. Wiring this up is a separate change. */
val ClayTertiaryLight = Color(0xFF5E8068)
val ClayTertiaryInkLight = Color(0xFFFFFFFF)
val ClayTertiaryContainerLight = Color(0xFFCDDDD0)
val ClayOnTertiaryContainerLight = Color(0xFF2F4536)
/** Decorative edges and dividers -- NOT a control boundary. See rule 2. */
val ClayOutlineLight = Color(0xFFAFA796)
/** Hairline dividers, a step quieter than outline. */
val ClayOutlineVariantLight = Color(0xFFD8D3C7)
val ClayErrorLight = Color(0xFFBA3B26)
/** 5.27:1 on the error red. Kept separate from the coral's ink: these were one shared value, so changing the button ink would have silently changed the error ink too. */
val ClayErrorInkLight = Color(0xFFFFF6F2)
val ClayErrorContainerLight = Color(0xFFF7DAD3)
val ClayOnErrorContainerLight = Color(0xFF7A2415)

// Material's surface-container ladder, dim -> bright. These are not
// decoration: AlertDialog paints surfaceContainerHigh, Card paints
// surfaceContainerLow, menus and sheets paint the others. Leaving them
// unmapped is what made the delete-confirmation dialog render in Material's
// lavender baseline (#ECE6F0) inside an otherwise clay app.
val ClaySurfaceContainerLowestLight = Color(0xFFFBF9F5)
val ClaySurfaceContainerLowLight = Color(0xFFF4F1EB)
val ClaySurfaceContainerLight = Color(0xFFEFECE6)
val ClaySurfaceContainerHighLight = Color(0xFFE7E3DB)
val ClaySurfaceContainerHighestLight = Color(0xFFE0DBD2)
val ClaySurfaceDimLight = Color(0xFFD9D4CB)
val ClaySurfaceBrightLight = Color(0xFFF7F4EF)

// Snackbar and anything else that inverts out of the page.
val ClayInverseSurfaceLight = Color(0xFF3A362F)
val ClayInverseOnSurfaceLight = Color(0xFFF1EDE4)
val ClayInversePrimaryLight = Color(0xFFF08863)

// ---- Dark ----
// The dark palette was already doing the right thing on nearly every
// pairing (its primary button measured 7.25:1 while light measured 2.71:1).
// What it inherited from light were the two structural gaps: an outline and
// a tonal container too close to their own ground to be seen.
val ClayBackgroundDark = Color(0xFF211E18)
val ClaySurfaceDark = Color(0xFF2A2620)
val ClaySurfaceVariantDark = Color(0xFF302B23)
val ClayInkDark = Color(0xFFEEE8DB)
/** 6.40:1 on background. Already passing; unchanged. */
val ClayInkSoftDark = Color(0xFFA8A08E)
val ClayAccentDark = Color(0xFFF08863)
/** 7.25:1. This is the pairing the light theme now copies. */
val ClayAccentInkOnFillDark = Color(0xFF241109)
val ClayAccentContainerDark = Color(0xFF7A3418)
/** 12.69:1 on background -- on a dark ground the accent is legible as text at full strength, so this is the accent itself rather than a darkened variant. */
val ClayAccentInkDark = Color(0xFFFFD9CB)
/** 1.44:1 against the page, up from 1.18:1. */
val ClaySecondaryContainerDark = Color(0xFF3F382C)
val ClayOnSecondaryContainerDark = Color(0xFFEEE8DB)
val ClayTertiaryDark = Color(0xFF93AD9C)
val ClayTertiaryInkDark = Color(0xFF0E1712)
val ClayTertiaryContainerDark = Color(0xFF35493C)
val ClayOnTertiaryContainerDark = Color(0xFFCFE2D5)
/** 2.40:1 against the page, up from 1.37:1. */
val ClayOutlineDark = Color(0xFF615948)
val ClayOutlineVariantDark = Color(0xFF3A352C)
val ClayErrorDark = Color(0xFFFF8A70)
val ClayErrorInkDark = Color(0xFF241109)
val ClayErrorContainerDark = Color(0xFF7A2415)
val ClayOnErrorContainerDark = Color(0xFFFFD9CF)

val ClaySurfaceContainerLowestDark = Color(0xFF171511)
val ClaySurfaceContainerLowDark = Color(0xFF242019)
val ClaySurfaceContainerDark = Color(0xFF2A2620)
val ClaySurfaceContainerHighDark = Color(0xFF332E26)
val ClaySurfaceContainerHighestDark = Color(0xFF3D372D)
val ClaySurfaceDimDark = Color(0xFF1B1813)
val ClaySurfaceBrightDark = Color(0xFF3A352C)

val ClayInverseSurfaceDark = Color(0xFFEEE8DB)
val ClayInverseOnSurfaceDark = Color(0xFF2A2620)
val ClayInversePrimaryDark = Color(0xFFB04423)