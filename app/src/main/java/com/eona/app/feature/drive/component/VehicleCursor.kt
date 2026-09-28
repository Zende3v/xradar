package com.eona.app.feature.drive.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.eona.app.data.preferences.VehicleType
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The driver's own position on the map, as the vehicle picked in Mon compte: seen from above,
 * nose to the north, the map turning it with the heading. The body takes the app's colour
 * ([bodyColor], ARGB), the windows a deep shade of it; a white rim and a soft shadow keep it
 * readable on any basemap, like the arrow it replaces. [sizePx] square, sized for this screen.
 */
internal fun vehicleCursorBitmap(type: VehicleType, bodyColor: Int, sizePx: Int, densityDpi: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    // MapLibre sizes an image by its density: exactly [sizePx] pixels of this screen.
    bitmap.density = densityDpi
    drawVehicle(Canvas(bitmap), type, sizePx.toFloat(), bodyColor, rimmed = true)
    return bitmap
}

/** The same drawing without its rim, for the choice in Mon compte. */
@Composable
fun VehicleGlyph(type: VehicleType, color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawBehind {
            val side = size.minDimension
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate((size.width - side) / 2f, (size.height - side) / 2f)
                drawVehicle(native, type, side, color.toArgb(), rimmed = false)
                native.restore()
            }
        },
    )
}

/** [type] in a [size] square, centred, nose up. Every shape is laid out in fractions of it. */
private fun drawVehicle(canvas: Canvas, type: VehicleType, size: Float, bodyColor: Int, rimmed: Boolean) {
    val tones = Tones(bodyColor)
    val layers = when (type) {
        VehicleType.Arrow -> listOf(Layer(Path().apply {
            moveTo(size * 0.5f, size * 0.175f)
            lineTo(size * 0.75f, size * 0.775f)
            lineTo(size * 0.5f, size * 0.625f)
            lineTo(size * 0.25f, size * 0.775f)
            close()
        }, tones.body, corner = 0.018f))
        VehicleType.Car -> car(size, tones, taxi = false)
        VehicleType.Taxi -> car(size, tones, taxi = true)
        VehicleType.Motorcycle -> motorcycle(size, tones)
        VehicleType.Truck -> truck(size, tones)
    }
    if (rimmed) {
        // The whole outline, drawn a little larger in white with its shadow, under the parts.
        val outline = Path()
        layers.forEach { outline.op(it.path, Path.Op.UNION) }
        canvas.drawPath(
            outline,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = size * RIM
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.WHITE
                setShadowLayer(size * SHADOW_BLUR, 0f, size * SHADOW_DROP, SHADOW_COLOR)
            },
        )
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    layers.forEach { layer ->
        paint.color = layer.color
        paint.pathEffect = if (layer.corner > 0f) CornerPathEffect(layer.corner * size) else null
        canvas.drawPath(layer.path, paint)
    }
}

/** A car: bonnet, windscreen, roof, rear window and headlights. The taxi wears its roof sign. */
private fun car(s: Float, t: Tones, taxi: Boolean): List<Layer> = buildList {
    add(Layer(rounded(s, 0.295f, 0.375f, 0.335f, 0.405f, 0.012f), t.body))
    add(Layer(rounded(s, 0.665f, 0.375f, 0.705f, 0.405f, 0.012f), t.body))
    add(Layer(rounded(s, 0.33f, 0.16f, 0.67f, 0.84f, front = 0.11f, back = 0.085f), t.body))
    add(Layer(pane(s, 0.355f, 0.36f, 0.64f, 0.455f, 0.385f, 0.615f), t.glass, corner = 0.02f))
    add(Layer(rounded(s, 0.385f, 0.455f, 0.615f, 0.685f, 0.03f), t.roof))
    add(Layer(pane(s, 0.685f, 0.385f, 0.615f, 0.755f, 0.405f, 0.595f), t.glass, corner = 0.02f))
    add(Layer(rounded(s, 0.375f, 0.195f, 0.435f, 0.215f, 0.01f), t.light))
    add(Layer(rounded(s, 0.565f, 0.195f, 0.635f, 0.215f, 0.01f), t.light))
    if (taxi) {
        add(Layer(rounded(s, 0.395f, 0.525f, 0.605f, 0.615f, 0.022f), t.signRim))
        add(Layer(rounded(s, 0.407f, 0.537f, 0.593f, 0.603f, 0.014f), t.sign))
    }
}

/** A lorry: a short cab with its windscreen, then the long box with its roof ribs. */
private fun truck(s: Float, t: Tones): List<Layer> = buildList {
    add(Layer(rounded(s, 0.285f, 0.12f, 0.33f, 0.155f, 0.012f), t.body))
    add(Layer(rounded(s, 0.67f, 0.12f, 0.715f, 0.155f, 0.012f), t.body))
    add(Layer(rounded(s, 0.33f, 0.08f, 0.67f, 0.29f, front = 0.055f, back = 0.02f), t.body))
    add(Layer(pane(s, 0.11f, 0.35f, 0.65f, 0.16f, 0.355f, 0.645f), t.glass, corner = 0.015f))
    add(Layer(rounded(s, 0.355f, 0.175f, 0.645f, 0.27f, 0.02f), t.roof))
    add(Layer(rounded(s, 0.365f, 0.088f, 0.42f, 0.1f, 0.006f), t.light))
    add(Layer(rounded(s, 0.58f, 0.088f, 0.635f, 0.1f, 0.006f), t.light))
    // The box, a little wider than the cab.
    add(Layer(rounded(s, 0.305f, 0.305f, 0.695f, 0.92f, 0.022f), t.body))
    for (y in floatArrayOf(0.43f, 0.555f, 0.68f, 0.805f)) {
        add(Layer(rounded(s, 0.33f, y, 0.67f, y + 0.012f, 0.006f), t.rib))
    }
}

/** A motorbike and its rider: tyres, fairing, handlebar, tank, seat, shoulders and helmet. */
private fun motorcycle(s: Float, t: Tones): List<Layer> {
    val helmet = circle(s, 0.5f, 0.478f, 0.064f)
    // The visor: the front of the helmet.
    val visor = Path(helmet).apply { op(rounded(s, 0.4f, 0.4f, 0.6f, 0.447f, 0f), Path.Op.INTERSECT) }
    return listOf(
        Layer(rounded(s, 0.478f, 0.14f, 0.522f, 0.3f, 0.022f), t.tyre),
        Layer(rounded(s, 0.476f, 0.66f, 0.524f, 0.84f, 0.024f), t.tyre),
        Layer(rounded(s, 0.445f, 0.235f, 0.555f, 0.36f, front = 0.05f, back = 0.02f), t.body),
        Layer(rounded(s, 0.466f, 0.252f, 0.534f, 0.268f, 0.008f), t.light),
        Layer(rounded(s, 0.44f, 0.5f, 0.56f, 0.73f, front = 0.03f, back = 0.05f), t.body),
        Layer(rounded(s, 0.425f, 0.37f, 0.575f, 0.51f, 0.06f), t.body),
        Layer(rounded(s, 0.305f, 0.345f, 0.695f, 0.378f, 0.016f), t.tyre),
        Layer(bar(s, 0.395f, 0.47f, 0.34f, 0.365f, 0.042f), t.glass),
        Layer(bar(s, 0.605f, 0.47f, 0.66f, 0.365f, 0.042f), t.glass),
        Layer(rounded(s, 0.36f, 0.435f, 0.64f, 0.555f, 0.06f), t.glass),
        Layer(helmet, t.body),
        Layer(visor, t.glass),
        Layer(circle(s, 0.485f, 0.492f, 0.018f), t.shine),
    )
}

/** One filled shape of the drawing; [corner] rounds a pane's corners (fraction of the size). */
private class Layer(val path: Path, val color: Int, val corner: Float = 0f)

/** The colours of one drawing, all from the body's. */
private class Tones(body: Int) {
    val body = body or OPAQUE
    val glass = mix(this.body, android.graphics.Color.BLACK, 0.62f)
    val roof = mix(this.body, android.graphics.Color.WHITE, 0.18f)
    val tyre = 0xFF1C1F24.toInt()
    val light = 0xF2FFFFFF.toInt()
    val sign = 0xFFFFD60A.toInt()
    val signRim = 0xB3000000.toInt()
    val rib = 0x24000000
    val shine = 0x59FFFFFF
}

/** [a] moved toward [b] by [t], opaque. */
private fun mix(a: Int, b: Int, t: Float): Int {
    fun channel(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).roundToInt()
    return OPAQUE or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** A rounded rectangle, [front] the radius of its top corners, [back] of its bottom ones. */
private fun rounded(s: Float, l: Float, t: Float, r: Float, b: Float, front: Float, back: Float = front): Path =
    Path().apply {
        val f = front * s
        val k = back * s
        addRoundRect(RectF(l * s, t * s, r * s, b * s), floatArrayOf(f, f, f, f, k, k, k, k), Path.Direction.CW)
    }

/** A window: its front edge at [top] from [tl] to [tr], its back edge at [bottom] from [bl] to [br]. */
private fun pane(s: Float, top: Float, tl: Float, tr: Float, bottom: Float, bl: Float, br: Float): Path =
    Path().apply {
        moveTo(tl * s, top * s)
        lineTo(tr * s, top * s)
        lineTo(br * s, bottom * s)
        lineTo(bl * s, bottom * s)
        close()
    }

private fun circle(s: Float, x: Float, y: Float, radius: Float): Path =
    Path().apply { addCircle(x * s, y * s, radius * s, Path.Direction.CW) }

/** A bar of [width] with round ends, from ([x1], [y1]) to ([x2], [y2]). */
private fun bar(s: Float, x1: Float, y1: Float, x2: Float, y2: Float, width: Float): Path {
    val dx = (x2 - x1) * s
    val dy = (y2 - y1) * s
    val half = width * s / 2
    return Path().apply {
        addRoundRect(RectF(-half, -half, hypot(dx, dy) + half, half), half, half, Path.Direction.CW)
        transform(
            Matrix().apply {
                postRotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                postTranslate(x1 * s, y1 * s)
            },
        )
    }
}

private const val OPAQUE = 0xFF shl 24
/** The white rim, as a share of the drawing's size (half of it outside the shapes). */
private const val RIM = 0.05f
private const val SHADOW_BLUR = 0.04f
private const val SHADOW_DROP = 0.012f
private const val SHADOW_COLOR = 0x59000000
