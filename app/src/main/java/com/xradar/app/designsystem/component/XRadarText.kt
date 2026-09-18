package com.xradar.app.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Canonical text primitive. Always renders from a design-system [TextStyle] token;
 * when [color] is unspecified it inherits the current content color.
 */
@Composable
fun XRadarText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = XRadarTheme.typography.body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    /** Room kept for this many lines even when the text takes fewer. */
    minLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}
