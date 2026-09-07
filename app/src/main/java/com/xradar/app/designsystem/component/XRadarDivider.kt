package com.xradar.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/** Hairline separator. */
@Composable
fun XRadarDivider(
    modifier: Modifier = Modifier,
    color: Color = XRadarTheme.colors.separator,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
