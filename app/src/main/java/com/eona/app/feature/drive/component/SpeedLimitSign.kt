package com.eona.app.feature.drive.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme

/** European-style circular speed-limit sign (red ring, black number on white). */
@Composable
fun SpeedLimitSign(limitKmh: Int, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(size * 0.12f, EonaTheme.colors.danger, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        EonaText(
            text = limitKmh.toString(),
            style = EonaTheme.typography.numeric.copy(
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color(0xFF0A0B0D),
        )
    }
}
