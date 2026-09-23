package com.eona.app.feature.drive.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.theme.EonaTheme

/** One of the sound choices shown when the bar is open. */
data class AudioOption<T>(val value: T, @DrawableRes val icon: Int, val label: String)

/**
 * An audio button over the dock. Closed, it is a plain round button showing what is on. Tapped, it
 * opens to the right and lays its choices side by side, like the noise-control picker of the
 * AirPods: the one in use is filled with the app's colour, a tap picks another and the bar closes.
 *
 * Only the width moves. The bar keeps its height open or closed, every choice stays in place (the
 * hidden ones have no width), and the highlight fades rather than appears: opening and closing is
 * one steady stretch, with nothing popping in or out on the way.
 */
@Composable
fun <T> AudioOptionBar(
    options: List<AudioOption<T>>,
    selected: T,
    label: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val colors = EonaTheme.colors
    val inset by animateDpAsState(if (open) 4.dp else 0.dp, AUDIO_DP, label = "audioInset")
    val chip = size - inset * 2
    Row(
        modifier = modifier
            .height(size)
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.62f))
            .border(1.dp, colors.border, CircleShape)
            .padding(horizontal = inset)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (open) 4.dp else 0.dp),
    ) {
        options.forEach { option ->
            val shown = open || option.value == selected
            val current = open && option.value == selected
            val width by animateDpAsState(if (shown) chip else 0.dp, AUDIO_DP, label = "audioChip")
            val fill by animateFloatAsState(if (current) 1f else 0f, AUDIO_FLOAT, label = "audioFill")
            val seen by animateFloatAsState(if (shown) 1f else 0f, AUDIO_FLOAT, label = "audioSeen")
            Box(
                modifier = Modifier
                    .width(width)
                    .height(chip)
                    .alpha(seen)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = fill))
                    .then(
                        if (shown) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (open) {
                                    onPick(option.value)
                                    onOpenChange(false)
                                } else {
                                    onOpenChange(true)
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        contentDescription = option.label
                        this.selected = option.value == selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                EonaIcon(
                    ImageVector.vectorResource(option.icon),
                    contentDescription = null,
                    tint = if (current) colors.onAccent else colors.textPrimary,
                    size = size * 0.5f,
                )
            }
        }
    }
}

/**
 * A HUD button making way for an open audio bar: it fades and shrinks a little where it stands.
 * Nothing moves around it — the bar simply grows over the place it leaves.
 */
@Composable
fun Modifier.audioMakesWay(hidden: Boolean): Modifier {
    val shown by animateFloatAsState(if (hidden) 0f else 1f, AUDIO_FLOAT, label = "audioMakesWay")
    return this.graphicsLayer {
        alpha = shown
        scaleX = 0.8f + 0.2f * shown
        scaleY = 0.8f + 0.2f * shown
    }
}

/** Opens and closes at one steady pace: under half a second, easing in and out, no bounce. */
private val AUDIO_DP = tween<Dp>(durationMillis = 420, easing = FastOutSlowInEasing)
private val AUDIO_FLOAT = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
