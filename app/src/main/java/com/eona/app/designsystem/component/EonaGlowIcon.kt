package com.eona.app.designsystem.component

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * An icon in one colour with a soft halo of that colour around its shape. Works with any
 * [Painter] (vector or bitmap): only its alpha is used, so a black PNG and a white vector
 * glow the same. The halo is a blurred copy of the icon's own silhouette, drawn behind
 * it — built once per size, and it works on every API level (no RenderEffect).
 */
@Composable
fun EonaGlowIcon(
    painter: Painter,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    glowRadius: Dp = 4.dp,
    glowAlpha: Float = 0.5f,
) {
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(size)
            .then(described)
            .drawWithCache {
                val width = this.size.width.roundToInt().coerceAtLeast(1)
                val height = this.size.height.roundToInt().coerceAtLeast(1)
                val silhouette = ImageBitmap(width, height)
                CanvasDrawScope().draw(this, layoutDirection, Canvas(silhouette), this.size) {
                    with(painter) { draw(this@draw.size, colorFilter = ColorFilter.tint(Color.White)) }
                }
                val blurPx = glowRadius.toPx()
                val glowOffset = IntArray(2)
                val glow = if (blurPx > 0f) {
                    silhouette.asAndroidBitmap().extractAlpha(
                        Paint().apply { maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) },
                        glowOffset,
                    )
                } else {
                    null
                }
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    color = tint.copy(alpha = tint.alpha * glowAlpha).toArgb()
                }
                val iconFilter = ColorFilter.tint(tint)
                onDrawBehind {
                    if (glow != null) {
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawBitmap(
                                glow,
                                glowOffset[0].toFloat(),
                                glowOffset[1].toFloat(),
                                glowPaint,
                            )
                        }
                    }
                    with(painter) { draw(this@onDrawBehind.size, colorFilter = iconFilter) }
                }
            },
    )
}
