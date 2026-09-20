package com.eona.app.feature.drive.component

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.RoadAlert
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign

/**
 * Every alert that is live right now, in one compact card. The alert in focus — the
 * nearest, unless the driver tapped another one — fills a single line (what, where, how
 * far); the others wait beneath it as small chips, each a tap away, and past [MAX_CHIPS]
 * the rest sit behind "+N", in a sheet that lists them all. Nothing is dropped.
 *
 * Swiping the card sideways throws the alert in focus away: [onDismiss] gets its key, the
 * next alert takes its place.
 */
@Composable
fun AlertStack(
    alerts: List<RoadAlert>,
    modifier: Modifier = Modifier,
    onDismiss: (String) -> Unit = {},
    /** Whether the driver can still say if this alert is there ("toujours là / plus là"). */
    canVote: (RoadAlert) -> Boolean = { false },
    onVote: (RoadAlert, Boolean) -> Unit = { _, _ -> },
) {
    if (alerts.isEmpty()) return
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing

    // Nearest first, without letting GPS noise swap two alerts a few metres apart.
    val lastOrder = remember { mutableListOf<String>() }
    val ordered = remember(alerts) {
        stableOrder(lastOrder, alerts, SWAP_MARGIN_M).also { list ->
            lastOrder.clear()
            lastOrder.addAll(list.map { it.key })
        }
    }

    // An alert the driver picked stays in focus for a while, then the nearest takes over.
    var pinned by remember { mutableStateOf<String?>(null) }
    val pinnedAlert = ordered.firstOrNull { it.key == pinned }
    LaunchedEffect(pinned, pinnedAlert == null) {
        if (pinned == null) return@LaunchedEffect
        if (pinnedAlert == null) {
            pinned = null
            return@LaunchedEffect
        }
        delay(PIN_MS)
        pinned = null
    }

    val focus = pinnedAlert ?: ordered.first()
    val isPinned = pinnedAlert != null && ordered.first().key != focus.key
    val others = ordered.filter { it.key != focus.key }
    val accent = focus.type.color()
    var listOpen by remember { mutableStateOf(false) }

    SwipeAway(swipeKey = focus.key, onDismiss = { onDismiss(focus.key) }, modifier = modifier) {
        EonaCard(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            // A picked alert is outlined in its colour; a tap on the card goes back to the nearest.
            onClick = if (isPinned) ({ pinned = null }) else null,
            shape = EonaTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(if (isPinned) 1.5.dp else 1.dp, if (isPinned) accent else colors.border),
            shadowElevation = EonaTheme.elevation.level4,
            contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm + spacing.hair),
        ) {
            FocusLine(focus)

            // Crowd reports carry a reliability; a fixed radar does not need one.
            if (focus.lastReportedLabel != null) {
                Spacer(Modifier.height(spacing.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(EonaTheme.shapes.pill)
                        .background(colors.surfaceHigh),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(focus.confidence)
                            .height(3.dp)
                            .clip(EonaTheme.shapes.pill)
                            .background(accent),
                    )
                }
            }

            if (canVote(focus)) {
                Spacer(Modifier.height(spacing.sm))
                VoteRow(onVote = { confirm -> onVote(focus, confirm) })
            }

            if (others.isNotEmpty()) {
                Spacer(Modifier.height(spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    others.take(MAX_CHIPS).forEach { alert ->
                        AlertChip(alert = alert, onClick = { pinned = alert.key })
                    }
                    val hidden = others.size - MAX_CHIPS
                    if (hidden > 0) {
                        MoreChip(count = hidden, onClick = { listOpen = true })
                    }
                }
            }
        }
    }

    if (listOpen) {
        AlertListSheet(
            alerts = ordered,
            focusKey = focus.key,
            onPick = { alert ->
                pinned = alert.key
                listOpen = false
            },
            onDismiss = { listOpen = false },
        )
    }
}

/** The alert in focus on one line: icon, what and where, distance and time to it. */
@Composable
private fun FocusLine(alert: RoadAlert) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val accent = alert.type.color()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(EonaTheme.shapes.md)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(alert.type.icon(), contentDescription = null, tint = accent, size = 22.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            EonaText(
                alert.title,
                style = EonaTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOf(alertSubtitle(alert), alert.lastReportedLabel.orEmpty())
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (detail.isNotEmpty()) {
                EonaText(
                    detail,
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            EonaText(
                formatDistance(alert.distanceMeters),
                style = EonaTheme.typography.title.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
            )
            EonaText("dans ${alert.etaSeconds} s", style = EonaTheme.typography.caption, color = colors.textTertiary)
        }
    }
}

/**
 * Drag sideways to throw an alert away: past [SWIPE_FRACTION] of the width, or on a fling
 * the same way, it leaves the screen and [onDismiss] runs; otherwise it springs back.
 * The drag follows the finger frame by frame (no coroutine per move), and a new
 * [swipeKey] — the next alert — starts back in place with a short fade in.
 */
@Composable
private fun SwipeAway(
    swipeKey: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var offset by remember(swipeKey) { mutableFloatStateOf(0f) }
    var leaving by remember(swipeKey) { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val appear = remember(swipeKey) { Animatable(0f) }
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(swipeKey) { appear.animateTo(1f, tween(APPEAR_MS)) }
    // Safety net: if the alert is still here long after leaving, bring the card back.
    LaunchedEffect(swipeKey, leaving) {
        if (!leaving) return@LaunchedEffect
        delay(STUCK_RESET_MS)
        offset = 0f
        leaving = false
    }

    val dragState = rememberDraggableState { delta -> if (!leaving) offset += delta }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .graphicsLayer {
                val w = width.coerceAtLeast(1).toFloat()
                val gone = (abs(offset) / w).coerceIn(0f, 1f)
                translationX = offset
                rotationZ = (offset / w) * SWIPE_TILT_DEG
                alpha = appear.value * (1f - gone * 0.85f)
                val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * appear.value
                scaleX = scale
                scaleY = scale
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = !leaving,
                onDragStopped = { velocity ->
                    val w = width.coerceAtLeast(1).toFloat()
                    val flungAway = abs(velocity) > SWIPE_FLING_PX_S &&
                        (abs(offset) < 1f || sign(velocity) == sign(offset))
                    if (flungAway || abs(offset) > w * SWIPE_FRACTION) {
                        leaving = true
                        val direction = if (flungAway) sign(velocity) else sign(offset)
                        animate(
                            initialValue = offset,
                            targetValue = direction * w * 1.15f,
                            initialVelocity = velocity,
                            animationSpec = tween(SWIPE_OUT_MS),
                        ) { value, _ -> offset = value }
                        latestOnDismiss()
                    } else {
                        animate(
                            initialValue = offset,
                            targetValue = 0f,
                            initialVelocity = velocity,
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                        ) { value, _ -> offset = value }
                    }
                },
            ),
    ) {
        content()
    }
}

/** "Toujours là" / "Plus là" for a crowd report close ahead: the driver's one voice on it. */
@Composable
private fun VoteRow(onVote: (Boolean) -> Unit) {
    val colors = EonaTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
        VoteButton("Toujours là", EonaIcons.Check, colors.accent, Modifier.weight(1f)) { onVote(true) }
        VoteButton("Plus là", EonaIcons.Close, colors.textSecondary, Modifier.weight(1f)) { onVote(false) }
    }
}

@Composable
private fun VoteButton(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val spacing = EonaTheme.spacing
    Row(
        modifier = modifier
            .heightIn(min = VOTE_HEIGHT)
            .clip(EonaTheme.shapes.pill)
            .background(tint.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.CenterHorizontally),
    ) {
        EonaIcon(icon, contentDescription = null, tint = tint, size = 16.dp)
        EonaText(
            label,
            style = EonaTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = EonaTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** Another live alert, as a chip: its icon in its colour and how far it is. */
@Composable
private fun AlertChip(alert: RoadAlert, onClick: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val accent = alert.type.color()
    Row(
        modifier = Modifier
            .clip(EonaTheme.shapes.pill)
            .background(accent.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        EonaIcon(alert.type.icon(), contentDescription = alert.title, tint = accent, size = 16.dp)
        EonaText(
            formatDistance(alert.distanceMeters),
            style = EonaTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** "+2": the alerts that do not fit as chips, one tap from the full list. */
@Composable
private fun MoreChip(count: Int, onClick: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Box(
        modifier = Modifier
            .clip(EonaTheme.shapes.pill)
            .background(colors.surfaceHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        EonaText(
            "+$count",
            style = EonaTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textSecondary,
        )
    }
}

/** An alert in the full list: its icon, what it is and its details, how far. */
@Composable
private fun AlertRow(alert: RoadAlert, onClick: () -> Unit, highlighted: Boolean) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val accent = alert.type.color()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.md)
            .background(if (highlighted) accent.copy(alpha = 0.10f) else colors.surfaceElevated.copy(alpha = 0f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = spacing.xs, horizontal = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(EonaTheme.shapes.sm)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(alert.type.icon(), contentDescription = null, tint = accent, size = 18.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            EonaText(
                alert.title,
                style = EonaTheme.typography.callout,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOf(alertSubtitle(alert), alert.lastReportedLabel.orEmpty())
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (detail.isNotEmpty()) {
                EonaText(
                    detail,
                    style = EonaTheme.typography.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        EonaText(
            formatDistance(alert.distanceMeters),
            style = EonaTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
        )
    }
}

/** All live alerts, nearest first; picking one brings it into focus on the HUD. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertListSheet(
    alerts: List<RoadAlert>,
    focusKey: String,
    onPick: (RoadAlert) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.md), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(EonaTheme.shapes.pill)
                        .background(colors.borderStrong),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            EonaText(
                if (alerts.size == 1) "1 alerte" else "${alerts.size} alertes",
                style = EonaTheme.typography.title,
                color = colors.textPrimary,
            )
            alerts.forEach { alert ->
                AlertRow(alert = alert, onClick = { onPick(alert) }, highlighted = alert.key == focusKey)
            }
            Spacer(Modifier.height(spacing.md))
        }
    }
}

/** Stable identity of an alert across frames (its radar or report id). */
internal val RoadAlert.key: String
    get() = id ?: "${type.name}:$title"

/**
 * Nearest first, except that two neighbours less than [marginM] apart keep the order they
 * had last time — GPS noise must not make the chips swap back and forth while driving.
 */
internal fun stableOrder(previous: List<String>, alerts: List<RoadAlert>, marginM: Int): List<RoadAlert> {
    val sorted = alerts.sortedBy { it.distanceMeters }.toMutableList()
    if (previous.isEmpty()) return sorted
    val rank = previous.withIndex().associate { (index, key) -> key to index }
    for (i in 0 until sorted.size - 1) {
        val near = sorted[i]
        val far = sorted[i + 1]
        val nearRank = rank[near.key] ?: continue
        val farRank = rank[far.key] ?: continue
        if (farRank < nearRank && far.distanceMeters - near.distanceMeters < marginM) {
            sorted[i] = far
            sorted[i + 1] = near
        }
    }
    return sorted
}

/** Touch height of the vote buttons, for a thumb while driving. */
private val VOTE_HEIGHT = 40.dp

/** Other alerts shown as chips before the rest fold into "+N". */
private const val MAX_CHIPS = 3

/** Two alerts closer than this keep their order: GPS noise must not shuffle the chips. */
private const val SWAP_MARGIN_M = 30

/** How long an alert the driver picked stays in focus before the nearest takes over. */
private const val PIN_MS = 10_000L

/** Share of the width a drag must cross to throw an alert away. */
private const val SWIPE_FRACTION = 0.3f

/** A fling faster than this (px/s), the same way as the drag, throws the alert away. */
private const val SWIPE_FLING_PX_S = 1_000f

private const val SWIPE_OUT_MS = 180

/** How far the card tilts at a full-width drag, in degrees. */
private const val SWIPE_TILT_DEG = 4f

private const val APPEAR_MS = 220
private const val APPEAR_SCALE = 0.96f

/** A card that left but whose alert never went away comes back after this long. */
private const val STUCK_RESET_MS = 1_500L
