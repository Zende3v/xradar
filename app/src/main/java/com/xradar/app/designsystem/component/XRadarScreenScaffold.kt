package com.xradar.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Standard secondary-screen frame: canvas background, a top bar (with optional
 * back + actions), and a weighted content area the caller fills (scroll column,
 * lazy list, or centered state).
 */
@Composable
fun XRadarScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(XRadarTheme.colors.canvas)) {
        XRadarTopBar(title = title, onBack = onBack, actions = actions)
        Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
    }
}
