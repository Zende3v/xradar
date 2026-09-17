package com.xradar.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * A white icon glowing on a dark tile, the same in light and dark: the report picker's look,
 * used for the Menu's icons.
 */
@Composable
fun XRadarGlowTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    iconSize: Dp = 18.dp,
    shape: Shape = XRadarTheme.shapes.sm,
) {
    val colors = XRadarTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.glowTile),
        contentAlignment = Alignment.Center,
    ) {
        XRadarGlowIcon(
            painter = rememberVectorPainter(icon),
            contentDescription = null,
            tint = colors.glowIcon,
            size = iconSize,
            glowRadius = (iconSize.value / 6f).coerceAtLeast(3f).dp,
        )
    }
}
