package com.eona.app.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Shared chrome for the on-device showcases (tokens & components). Preview-only —
 * not part of the shipping UI. Keeps both galleries visually identical.
 */
@Composable
fun ShowcaseScaffold(content: @Composable ColumnScope.() -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.xxxl),
        content = content,
    )
}

@Composable
fun ShowcaseSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.lg)) {
        Text(
            text = label.uppercase(),
            style = EonaTheme.typography.caption,
            color = EonaTheme.colors.textTertiary,
        )
        content()
    }
}
