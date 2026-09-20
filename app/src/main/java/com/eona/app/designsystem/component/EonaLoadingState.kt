package com.eona.app.designsystem.component

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
import com.eona.app.designsystem.theme.EonaTheme

/** Centered loading spinner with an optional label. */
@Composable
fun EonaLoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = EonaTheme.colors.accent,
                strokeWidth = 3.dp,
            )
            if (label != null) {
                EonaText(label, style = EonaTheme.typography.callout, color = EonaTheme.colors.textSecondary)
            }
        }
    }
}
