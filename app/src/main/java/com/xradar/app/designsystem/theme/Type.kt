package com.xradar.app.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Single point where the app's font is defined. Swapping in a bundled family
 * (e.g. an SF-substitute like Inter) later is a one-line change here; every
 * token below inherits from it.
 */
private val XRadarFontFamily = FontFamily.Default

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    spacingEm: Double = 0.0,
    features: String? = null,
) = TextStyle(
    fontFamily = XRadarFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacingEm.em,
    fontFeatureSettings = features,
)

/**
 * Semantic type scale, tuned close to the iOS ramp (17sp body, tight tracking on
 * large sizes). Components reference these tokens — never a raw [TextStyle].
 */
@Immutable
data class XRadarTypography(
    val displayHero: TextStyle,
    val display: TextStyle,
    val titleLarge: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val numeric: TextStyle,
)

val XRadarDefaultTypography = XRadarTypography(
    displayHero = style(88, 88, FontWeight.Bold, -0.03, features = "tnum"),
    display = style(40, 44, FontWeight.SemiBold, -0.02),
    titleLarge = style(28, 34, FontWeight.SemiBold, -0.02),
    title = style(22, 28, FontWeight.SemiBold, -0.01),
    headline = style(17, 22, FontWeight.SemiBold, -0.01),
    body = style(17, 24, FontWeight.Normal, -0.006),
    bodyStrong = style(17, 24, FontWeight.Medium, -0.006),
    callout = style(16, 21, FontWeight.Normal),
    subhead = style(15, 20, FontWeight.Normal),
    footnote = style(13, 18, FontWeight.Normal),
    caption = style(12, 16, FontWeight.Medium, 0.01),
    label = style(16, 20, FontWeight.SemiBold, -0.01),
    // Tabular figures for speed / ETA / distances so digits don't jitter.
    numeric = style(17, 22, FontWeight.Medium, features = "tnum"),
)

val LocalXRadarTypography = staticCompositionLocalOf { XRadarDefaultTypography }
