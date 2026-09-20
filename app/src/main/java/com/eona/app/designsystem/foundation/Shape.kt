package com.eona.app.designsystem.foundation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Corner-radius scale. Continuous, iOS-like roundness — softer than Material
 * defaults. `pill` is fully rounded for chips/badges.
 */
@Immutable
data class EonaShapes(
    val xs: Shape = RoundedCornerShape(6.dp),
    val sm: Shape = RoundedCornerShape(10.dp),
    val md: Shape = RoundedCornerShape(14.dp),
    val lg: Shape = RoundedCornerShape(18.dp),
    val xl: Shape = RoundedCornerShape(24.dp),
    val xxl: Shape = RoundedCornerShape(32.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
)

val LocalEonaShapes = staticCompositionLocalOf { EonaShapes() }
