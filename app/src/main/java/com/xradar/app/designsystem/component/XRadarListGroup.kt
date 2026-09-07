package com.xradar.app.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * iOS-style grouped list: an optional uppercased header above a single elevated
 * card that holds the rows. Callers place [XRadarListRow]s (and [XRadarDivider]s
 * between them) in [content].
 */
@Composable
fun XRadarListGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = XRadarTheme.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (title != null) {
            XRadarText(
                text = title.uppercase(),
                style = XRadarTheme.typography.caption,
                color = XRadarTheme.colors.textTertiary,
                modifier = Modifier.padding(start = spacing.md),
            )
        }
        XRadarCard(
            shadowElevation = XRadarTheme.elevation.level1,
            contentPadding = PaddingValues(0.dp),
            content = content,
        )
    }
}
