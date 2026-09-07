package com.xradar.app.designsystem.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4pt-based spacing scale. Every gap, padding and inset in the app comes from
 * one of these named steps so rhythm stays consistent across screens.
 */
@Immutable
data class XRadarSpacing(
    val none: Dp = 0.dp,
    val hair: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 40.dp,
    val giant: Dp = 48.dp,
    val colossal: Dp = 64.dp,
)

val LocalXRadarSpacing = staticCompositionLocalOf { XRadarSpacing() }
