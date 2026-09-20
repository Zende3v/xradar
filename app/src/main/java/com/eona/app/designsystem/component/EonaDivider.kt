package com.eona.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.theme.EonaTheme

/** Hairline separator. */
@Composable
fun EonaDivider(
    modifier: Modifier = Modifier,
    color: Color = EonaTheme.colors.separator,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
