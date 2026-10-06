package com.eona.app.feature.subscription

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radar EONA vivant, puis titre et promesse (iOS EonaPlusHero). Animation suspendue hors écran,
 * en arrière-plan, sous une feuille ([animationsEnabled]) ou avec les animations coupées.
 */
@Composable
fun EonaPlusHero(
    subtitle: String,
    modifier: Modifier = Modifier,
    title: String = "EONA+",
    compact: Boolean = false,
    animationsEnabled: Boolean = true,
) {
    val context = LocalContext.current
    // Animations coupées dans les réglages Android : radar figé.
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var visible by remember { mutableStateOf(true) }
    var time by remember { mutableFloatStateOf(0f) }
    val running = animationsEnabled && resumed && visible && !reduceMotion
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            // Environ 30 images par seconde, comme iOS.
            delay(FRAME_MS)
            withFrameNanos { now ->
                time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xxxl)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 130.dp else 180.dp)
                .clearAndSetSemantics { }
                .onGloballyPositioned { coordinates ->
                    // Visible si au moins un dixième dépasse dans la fenêtre.
                    val shown = coordinates.boundsInWindow().height
                    visible = coordinates.size.height > 0 && shown >= coordinates.size.height * 0.1f
                },
            contentAlignment = Alignment.Center,
        ) {
            Particles(time = { time }, compact = compact, modifier = Modifier.fillMaxSize())
            val emblem = if (compact) 94.dp else 130.dp
            val lift = with(LocalDensity.current) { 3.5.dp.toPx() }
            RadarEmblem(
                time = { time },
                modifier = Modifier
                    .size(emblem)
                    .graphicsLayer {
                        val t = time.toDouble()
                        rotationZ = (sin(t * 0.33) * 7.0).toFloat()
                        // Axe (1 ; 0,6 ; 0) : bascule partagée entre X et Y.
                        val tilt = (sin(t * 0.46) * 10.0).toFloat()
                        rotationX = tilt * 0.857f
                        rotationY = tilt * 0.514f
                        cameraDistance = 6f * density
                        translationY = (sin(t * 0.72) * lift).toFloat()
                    },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
            EonaText(
                title,
                style = EonaTheme.typography.titleLarge.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
                color = EonaPlusStyle.primary,
                modifier = Modifier.semantics { heading() },
            )
            EonaText(subtitle, style = EonaTheme.typography.body, color = EonaPlusStyle.secondary)
        }
    }
}

/** Particules pastel en orbite lente, scintillantes (+15 % en nombre et en mouvement). */
@Composable
private fun Particles(time: () -> Float, compact: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    val capPx = with(density) { (if (compact) 138.dp else 160.dp).toPx() }
    val big = with(density) { 4.5.dp.toPx() }
    val small = with(density) { 2.5.dp.toPx() }
    Canvas(modifier) {
        val t = time().toDouble()
        val cx = size.width / 2
        val cy = size.height / 2
        val horizontal = min(size.width * 0.44f, capPx)
        val vertical = size.height * 0.43f
        for (index in 0 until PARTICLE_COUNT) {
            val seed = index.toDouble()
            val angle = seed * 2.3999632297 + t * PARTICLE_MOTION * (if (index % 2 == 0) 0.04 else -0.035)
            val radius = 0.76 + (index % 4) * 0.07
            val x = cx + (cos(angle) * horizontal * radius).toFloat()
            val y = cy + (sin(angle) * vertical * radius).toFloat()
            val twinkle = ((sin(t * 0.85 + seed * 1.7) + 1.0) * 0.5).toFloat()
            val diameter = (if (index % 4 == 0) big else small) * (0.75f + twinkle * 0.35f)
            drawCircle(
                color = EonaPlusStyle.spectrum[index % EonaPlusStyle.spectrum.size],
                radius = diameter / 2,
                center = Offset(x, y),
                alpha = 0.25f + twinkle * 0.65f,
            )
        }
    }
}

/** Emblème : arc ouvert en bas, anneau, aiguille nord-est, point ; dégradé pastel tournant. */
@Composable
private fun RadarEmblem(time: () -> Float, modifier: Modifier) {
    val colours = remember { (EonaPlusStyle.spectrum + EonaPlusStyle.spectrum.first()).map { it.toArgb() }.toIntArray() }
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val center = Offset(size.width / 2, size.height / 2)
        val start = (210.0 + sin(time() * 0.18) * 32.0).toFloat()
        val brush = object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                android.graphics.SweepGradient(center.x, center.y, colours, null).apply {
                    setLocalMatrix(android.graphics.Matrix().apply { setRotate(start, center.x, center.y) })
                }
        }
        // Arc extérieur : de 135° à 45°, ouvert en bas.
        val outer = side * 0.86f
        drawArc(
            brush = brush,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - outer / 2, center.y - outer / 2),
            size = Size(outer, outer),
            alpha = 0.66f,
            style = Stroke(width = side * 0.105f, cap = StrokeCap.Round),
        )
        drawCircle(brush = brush, radius = side * 0.28f, center = center, style = Stroke(width = side * 0.11f))
        drawLine(
            brush = brush,
            start = center,
            end = Offset(size.width * 0.84f, size.height * 0.16f),
            strokeWidth = side * 0.105f,
            cap = StrokeCap.Round,
        )
        drawCircle(brush = brush, radius = side * 0.1025f, center = center)
    }
}

private const val FRAME_MS = 20L
private const val PARTICLE_MOTION = 1.15
private val PARTICLE_COUNT = (18.0 * 1.15).roundToInt()
