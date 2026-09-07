package com.xradar.app.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic elevation steps. Used both as z-order intent and as the basis for the
 * soft-shadow modifiers introduced with the component layer.
 */
@Immutable
data class XRadarElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 8.dp,
    val level4: Dp = 16.dp,
    val level5: Dp = 24.dp,
)

val LocalXRadarElevation = staticCompositionLocalOf { XRadarElevation() }
