@file:OptIn(ExperimentalTextApi::class)

package com.example.snapfindai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.snapfindai.R

// Fredoka and Karla are variable fonts (one file, a weight axis) -- each
// Font() entry below asks the same file for a different instance via
// FontVariationSettings. Below API 26 (no variable-font support), every
// entry falls back to the file's single default instance instead of the
// requested weight -- a softer type scale on old devices, not a crash.
private val FredokaFamily = FontFamily(
    Font(R.font.fredoka_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.fredoka_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.fredoka_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val KarlaFamily = FontFamily(
    Font(R.font.karla_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.karla_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.karla_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.karla_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// Fredoka (soft, rounded, a little playful -- the "clay" of the theme) for
// anything headline-sized; Karla (a plain, warm grotesque) for everything
// meant to be read at length -- body copy, labels, button text.
val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = FredokaFamily, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = FredokaFamily, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FredokaFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FredokaFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = KarlaFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontFamily = KarlaFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = KarlaFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = KarlaFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = KarlaFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = KarlaFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)
