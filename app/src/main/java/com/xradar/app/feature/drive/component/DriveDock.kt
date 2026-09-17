package com.xradar.app.feature.drive.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.SpeedStatus
import com.xradar.app.core.model.TripInfo
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarSwitch
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * "E3" — the drop-up dock at the bottom of the HUD, replacing the old modal options
 * sheet. It floats above the map (inset from every edge, frosted like the rest of the
 * HUD), shows the live speed with the limit that applies right here, keeps the current
 * maneuver in sight while navigating, and drags up to reveal the full options.
 */
@Composable
fun DriveDock(
    speedKmh: Int,
    limitKmh: Int?,
    status: SpeedStatus?,
    searching: Boolean,
    trip: TripInfo?,
    modifier: Modifier = Modifier,
    /** Seconds left on the light we are waiting at — E4, fed by a later release. */
    redLightSeconds: Int? = null,
    /** True as soon as the dock is pulled open, so the HUD can clear the way. */
    onOpenChange: (Boolean) -> Unit = {},
    /** Tap on the limit sign: propose a new limit (null = the sign is not tappable). */
    onLimitClick: (() -> Unit)? = null,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // At rest the dock takes a fifth of the screen; pulled up, four fifths.
        // Measured on the screen, not on the leftover space, so an incoming alert
        // above the dock never resizes it.
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val collapsedHeight = if (trip != null) COLLAPSED_WITH_TRIP else COLLAPSED_PLAIN
        val expandedHeight = (screenHeight * EXPANDED_FRACTION).coerceAtMost(maxHeight).coerceAtLeast(collapsedHeight)
        val travelPx = with(LocalDensity.current) {
            (expandedHeight - collapsedHeight).toPx().coerceAtLeast(1f)
        }
        val open = progress.value
        val isOpen = open > OPEN_THRESHOLD
        LaunchedEffect(isOpen) { onOpenChange(isOpen) }

        fun settle(toOpen: Boolean) {
            scope.launch {
                progress.animateTo(
                    targetValue = if (toOpen) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                )
            }
        }

        val drag = rememberDraggableState { delta ->
            scope.launch { progress.snapTo((progress.value - delta / travelPx).coerceIn(0f, 1f)) }
        }

        XRadarSurface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    min = collapsedHeight,
                    max = lerp(collapsedHeight, expandedHeight, open),
                ),
            shape = XRadarTheme.shapes.xxl,
            color = colors.surface.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            settle(
                                when {
                                    velocity < -FLING_PX_S -> true
                                    velocity > FLING_PX_S -> false
                                    else -> progress.value > 0.4f
                                },
                            )
                        },
                    )
                    .padding(horizontal = spacing.md)
                    .padding(bottom = spacing.md),
            ) {
                Handle(onTap = { settle(progress.value < 0.5f) })

                if (trip != null) {
                    TripLine(trip)
                    Spacer(Modifier.height(spacing.sm))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SpeedCard(
                        speedKmh = speedKmh,
                        limitKmh = limitKmh,
                        status = status,
                        searching = searching,
                        onLimitClick = onLimitClick,
                        modifier = Modifier.weight(1.45f),
                    )
                    RedLightCard(secondsLeft = redLightSeconds, modifier = Modifier.weight(1f))
                    OptionsCard(open = open, onClick = { settle(progress.value < 0.5f) })
                }

                if (open > 0.02f) {
                    Spacer(Modifier.height(spacing.md))
                    Column(
                        modifier = Modifier
                            .alpha(open)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(spacing.lg),
                    ) {
                        OptionsBody()
                        Spacer(Modifier.height(spacing.sm))
                    }
                }
            }
        }
    }
}

@Composable
private fun Handle(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(vertical = XRadarTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 4.dp)
                .clip(XRadarTheme.shapes.pill)
                .background(XRadarTheme.colors.borderStrong),
        )
    }
}

/** Trip figures, always in sight: time left, distance left, arrival time. */
@Composable
private fun TripLine(trip: TripInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = XRadarTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
    ) {
        Cluster(trip.remainingLabel, "Restantes", XRadarTheme.colors.accent, Modifier.weight(1f))
        Cluster(trip.distanceLabel, "Distance", XRadarTheme.colors.textPrimary, Modifier.weight(1f))
        Cluster(trip.arrivalLabel, "Arrivée", XRadarTheme.colors.textPrimary, Modifier.weight(1f))
    }
}

/** Card 1: the real speed, with the limit that applies right here beside it. */
@Composable
private fun SpeedCard(
    speedKmh: Int,
    limitKmh: Int?,
    status: SpeedStatus?,
    searching: Boolean,
    onLimitClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = XRadarTheme.colors
    val limitInteraction = remember { MutableInteractionSource() }
    val target = when {
        searching -> colors.textSecondary
        status == SpeedStatus.Over -> colors.speedOver
        status == SpeedStatus.Caution -> colors.warning
        status == SpeedStatus.Safe -> colors.speedSafe
        else -> colors.textPrimary
    }
    // A fix lands once a second; the number walks there linearly so the eye never sees
    // a jump — the same illusion the map arrow runs on.
    val shown by animateFloatAsState(
        targetValue = speedKmh.toFloat(),
        animationSpec = tween(durationMillis = SPEED_GLIDE_MS, easing = LinearEasing),
        label = "speed",
    )
    val color by animateColorAsState(target, label = "speedColor")

    DockCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = XRadarTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                XRadarText(
                    text = if (searching) "--" else shown.roundToInt().coerceAtLeast(0).toString(),
                    style = XRadarTheme.typography.displayHero.copy(
                        fontSize = 40.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = color,
                    maxLines = 1,
                )
                XRadarText(
                    text = if (searching) "Recherche GPS…" else "km/h",
                    style = XRadarTheme.typography.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
            // The sign is also the way to propose a new limit when the road's has changed.
            val signModifier = if (onLimitClick == null) {
                Modifier
            } else {
                Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = limitInteraction,
                        indication = null,
                        onClickLabel = "Signaler une nouvelle limitation",
                        onClick = onLimitClick,
                    )
            }
            if (limitKmh != null) {
                SpeedLimitSign(limitKmh = limitKmh, modifier = signModifier, size = 50.dp)
            } else {
                UnknownLimitSign(size = 50.dp, modifier = signModifier)
            }
        }
    }
}

/** The limit slot always holds its place — an empty sign reads better than a jump. */
@Composable
internal fun UnknownLimitSign(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(XRadarTheme.colors.surfaceHigh)
            .border(size * 0.10f, XRadarTheme.colors.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        XRadarText("--", style = XRadarTheme.typography.bodyStrong, color = XRadarTheme.colors.textTertiary)
    }
}

/** "E4": how long the red light we are stopped at still has to run. */
@Composable
private fun RedLightCard(secondsLeft: Int?, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val active = secondsLeft != null
    DockCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = XRadarTheme.spacing.sm),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.xs),
            ) {
                XRadarIcon(
                    XRadarIcons.TrafficLight,
                    contentDescription = null,
                    tint = if (active) colors.danger else colors.textTertiary,
                    size = 20.dp,
                )
                XRadarText(
                    text = if (active) "${secondsLeft}s" else "--",
                    style = XRadarTheme.typography.title,
                    color = if (active) colors.textPrimary else colors.textTertiary,
                    maxLines = 1,
                )
            }
            XRadarText(
                "Feu rouge",
                style = XRadarTheme.typography.caption,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/** Pulls the dock open onto the options. */
@Composable
private fun OptionsCard(open: Float, onClick: () -> Unit) {
    val colors = XRadarTheme.colors
    DockCard(
        modifier = Modifier
            .size(width = 72.dp, height = DOCK_CARD_HEIGHT)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        color = if (open > 0.5f) colors.accent.copy(alpha = 0.18f) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            XRadarIcon(
                if (open > 0.5f) XRadarIcons.ChevronDown else XRadarIcons.ChevronUp,
                contentDescription = null,
                tint = colors.textSecondary,
                size = 16.dp,
            )
            Spacer(Modifier.height(2.dp))
            XRadarIcon(XRadarIcons.Settings, contentDescription = null, tint = colors.textSecondary, size = 20.dp)
            XRadarText(
                "Options",
                style = XRadarTheme.typography.caption,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/** The pale blocks of the sketch: a lighter inner card on the frosted dock. */
@Composable
private fun DockCard(
    modifier: Modifier = Modifier,
    color: Color? = null,
    content: @Composable () -> Unit,
) {
    XRadarSurface(
        modifier = modifier.height(DOCK_CARD_HEIGHT),
        shape = XRadarTheme.shapes.lg,
        // Same grey family as the dock itself: an elevated fill here turned the cards
        // into pale slabs floating on the frosted panel.
        color = color ?: XRadarTheme.colors.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, XRadarTheme.colors.border),
        content = content,
    )
}

@Composable
private fun Cluster(value: String, label: String, valueColor: Color, modifier: Modifier) {
    Column(modifier) {
        XRadarText(value, style = XRadarTheme.typography.bodyStrong, color = valueColor, maxLines = 1)
        XRadarText(label.uppercase(), style = XRadarTheme.typography.caption, color = XRadarTheme.colors.textTertiary)
    }
}

/** The alerts, one switch per category, and the route options. */
@Composable
private fun OptionsBody() {
    val colors = XRadarTheme.colors
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()

    val groupColor = colors.surface.copy(alpha = 0.45f)

    XRadarListGroup(title = "Alertes", color = groupColor, shadowElevation = 0.dp) {
        Toggle("Radar fixe", XRadarIcons.Radar, colors.radarFixed, prefs.radarFixed) {
            AppPreferences.updateAlerts { it.copy(radarFixed = !it.radarFixed) }
        }
        ReportType.ALERT_OPTIONS.forEach { type ->
            RowDivider()
            Toggle(type.label, type.alertType.icon(), type.alertType.color(), prefs.shows(type)) {
                AppPreferences.updateAlerts { it.toggled(type) }
            }
        }
    }

    XRadarListGroup(title = "Itinéraire", color = groupColor, shadowElevation = 0.dp) {
        Toggle("Éviter les péages", XRadarIcons.Toll, colors.controlZone, settings.avoidTolls) {
            AppPreferences.updateSettings { it.copy(avoidTolls = !it.avoidTolls) }
        }
        RowDivider()
        Toggle("Éviter les autoroutes", XRadarIcons.Navigation, colors.accent, settings.avoidHighways) {
            AppPreferences.updateSettings { it.copy(avoidHighways = !it.avoidHighways) }
        }
        RowDivider()
        Toggle("Éviter les bouchons", XRadarIcons.Warning, colors.hazard, settings.avoidTraffic) {
            AppPreferences.updateSettings { it.copy(avoidTraffic = !it.avoidTraffic) }
        }
    }
}

@Composable
private fun Toggle(title: String, icon: ImageVector, tint: Color, checked: Boolean, onToggle: () -> Unit) {
    XRadarListRow(
        title = title,
        leadingIcon = icon,
        leadingTint = tint,
        onClick = onToggle,
        trailing = { XRadarSwitch(checked = checked, onCheckedChange = { onToggle() }) },
    )
}

@Composable
private fun RowDivider() = XRadarDivider(Modifier.padding(start = 58.dp))

private val DOCK_CARD_HEIGHT = 82.dp
/** Exactly the handle + the cards (+ the trip line when there is one) — no dead strip. */
private val COLLAPSED_PLAIN = 124.dp
private val COLLAPSED_WITH_TRIP = 166.dp
private const val EXPANDED_FRACTION = 0.80f
private const val SPEED_GLIDE_MS = 900
private const val FLING_PX_S = 350f
/** Past this much opening, the dock owns the screen and the map controls hide. */
private const val OPEN_THRESHOLD = 0.12f

@Preview(name = "Dock · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun DriveDockPreview() {
    XRadarTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            DriveDock(
                speedKmh = 62,
                limitKmh = 50,
                status = SpeedStatus.Over,
                searching = false,
                trip = TripInfo("08:42", "12,4 km", "20:14"),
            )
        }
    }
}
