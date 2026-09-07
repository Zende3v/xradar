package com.xradar.app.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/** Centered loading spinner with an optional label. */
@Composable
fun XRadarLoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = XRadarTheme.colors.accent,
                strokeWidth = 3.dp,
            )
            if (label != null) {
                XRadarText(label, style = XRadarTheme.typography.callout, color = XRadarTheme.colors.textSecondary)
            }
        }
    }
}
