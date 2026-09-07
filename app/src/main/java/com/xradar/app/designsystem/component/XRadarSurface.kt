package com.xradar.app.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Base container: a themed background with a shape, optional hairline border and
 * optional soft shadow. Sets [LocalContentColor] so children inherit a legible
 * foreground. Everything else (cards, sheets, panels) builds on this.
 */
@Composable
fun XRadarSurface(
    modifier: Modifier = Modifier,
    shape: Shape = XRadarTheme.shapes.lg,
    color: Color = XRadarTheme.colors.surface,
    contentColor: Color = XRadarTheme.colors.textPrimary,
    border: BorderStroke? = BorderStroke(1.dp, XRadarTheme.colors.border),
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .shadow(shadowElevation, shape, clip = false)
                .clip(shape)
                .background(color)
                .then(if (border != null) Modifier.border(border, shape) else Modifier),
        ) {
            content()
        }
    }
}
