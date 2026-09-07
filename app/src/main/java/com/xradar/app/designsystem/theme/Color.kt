package com.xradar.app.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw palette. Never referenced directly from UI code — always go through the
 * semantic [XRadarColors] tokens so the whole app re-themes from one place.
 *
 * The system-color values mirror the iOS system palette (dark/light variants)
 * to anchor the Apple-inspired direction; neutrals are custom, dark-first.
 */
internal object Palette {
    // Neutral ink (dark surfaces, darkest -> lightest)
    val Ink0 = Color(0xFF06070A)
    val Ink1 = Color(0xFF0E1014)
    val Ink2 = Color(0xFF16191F)
    val Ink3 = Color(0xFF1E222A)
    val Ink4 = Color(0xFF272C36)

    // Neutral snow (light surfaces)
    val Snow0 = Color(0xFFF6F8FB)
    val Snow1 = Color(0xFFFFFFFF)

    // Foreground on dark
    val TextHi = Color(0xFFF5F7FA)
    val TextMid = Color(0xFFA9B0BC)
    val TextLo = Color(0xFF6C7280)
    val TextDim = Color(0xFF454B55)

    // Foreground on light
    val TextHiL = Color(0xFF0A0B0D)
    val TextMidL = Color(0xFF54596B)
    val TextLoL = Color(0xFF8A8F9C)
    val TextDimL = Color(0xFFB6BAC4)

    // System colors — dark variants
    val GreenD = Color(0xFF30D158)
    val RedD = Color(0xFFFF453A)
    val OrangeD = Color(0xFFFF9F0A)
    val YellowD = Color(0xFFFFD60A)
    val IndigoD = Color(0xFF5E5CE6)
    val TealD = Color(0xFF64D2FF)

    // System colors — light variants
    val GreenL = Color(0xFF34C759)
    val RedL = Color(0xFFFF3B30)
    val OrangeL = Color(0xFFFF9500)
    val YellowL = Color(0xFFFFCC00)
    val IndigoL = Color(0xFF5856D6)
    val TealL = Color(0xFF32ADE6)

    // Accent — cyan/teal (interactive + navigation route)
    val CyanD = Color(0xFF2CD5E0)
    val CyanPressedD = Color(0xFF16B7C4)
    val OnAccentInkDark = Color(0xFF062024)
    val CyanL = Color(0xFF0AA7B4)
    val CyanPressedL = Color(0xFF0B8592)
}

/**
 * Semantic color tokens. This is the contract every component reads from.
 * Add a token here rather than hard-coding a [Color] anywhere in the UI.
 */
@Immutable
data class XRadarColors(
    val isDark: Boolean,
    // Layered surfaces (canvas is the deepest — the map/void)
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHigh: Color,
    val scrim: Color,
    // Lines
    val border: Color,
    val borderStrong: Color,
    val separator: Color,
    // Foreground
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    // Brand / interactive
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    // Feedback
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    // Road-safety domain semantics
    val speedSafe: Color,
    val speedOver: Color,
    val radarFixed: Color,
    val radarMobile: Color,
    val controlZone: Color,
    val hazard: Color,
)

val XRadarDarkColors = XRadarColors(
    isDark = true,
    canvas = Palette.Ink0,
    surface = Palette.Ink1,
    surfaceElevated = Palette.Ink2,
    surfaceHigh = Palette.Ink3,
    scrim = Color(0xCC000000),
    border = Color(0x14FFFFFF),
    borderStrong = Color(0x24FFFFFF),
    separator = Color(0x1FFFFFFF),
    textPrimary = Palette.TextHi,
    textSecondary = Palette.TextMid,
    textTertiary = Palette.TextLo,
    textDisabled = Palette.TextDim,
    accent = Palette.CyanD,
    accentPressed = Palette.CyanPressedD,
    onAccent = Palette.OnAccentInkDark,
    success = Palette.GreenD,
    warning = Palette.OrangeD,
    danger = Palette.RedD,
    info = Palette.TealD,
    speedSafe = Palette.GreenD,
    speedOver = Palette.RedD,
    radarFixed = Palette.RedD,
    radarMobile = Palette.OrangeD,
    controlZone = Palette.IndigoD,
    hazard = Palette.YellowD,
)

val XRadarLightColors = XRadarColors(
    isDark = false,
    canvas = Palette.Snow0,
    surface = Palette.Snow1,
    surfaceElevated = Palette.Snow1,
    surfaceHigh = Palette.Snow1,
    scrim = Color(0x66000000),
    border = Color(0x14000000),
    borderStrong = Color(0x24000000),
    separator = Color(0x1A000000),
    textPrimary = Palette.TextHiL,
    textSecondary = Palette.TextMidL,
    textTertiary = Palette.TextLoL,
    textDisabled = Palette.TextDimL,
    accent = Palette.CyanL,
    accentPressed = Palette.CyanPressedL,
    onAccent = Color(0xFFFFFFFF),
    success = Palette.GreenL,
    warning = Palette.OrangeL,
    danger = Palette.RedL,
    info = Palette.TealL,
    speedSafe = Palette.GreenL,
    speedOver = Palette.RedL,
    radarFixed = Palette.RedL,
    radarMobile = Palette.OrangeL,
    controlZone = Palette.IndigoL,
    hazard = Palette.YellowL,
)
