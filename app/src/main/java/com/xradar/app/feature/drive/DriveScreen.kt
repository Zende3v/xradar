package com.xradar.app.feature.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xradar.app.core.model.GpsSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xradar.app.core.model.AlertType
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.core.model.TripInfo
import com.xradar.app.data.routing.ActiveTripRepository
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarIconButton
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.drive.component.AlertSheet
import com.xradar.app.feature.drive.component.DriveMap
import com.xradar.app.feature.drive.component.GuidanceBanner
import com.xradar.app.feature.drive.component.SpeedPanel
import com.xradar.app.feature.drive.component.TripStrip

/** Entry point wired to the Phase-1 simulation. Swap the source in Phase 2. */
@Composable
fun DriveRoute(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DriveViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val account by com.xradar.app.data.account.AccountRepository.account.collectAsStateWithLifecycle()
    DriveScreen(
        state = state,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onStopNavigation = { ActiveTripRepository.clear() },
        onReport = viewModel::report,
        isAdmin = account?.role == com.xradar.app.core.model.Role.Admin,
        onDeleteReport = viewModel::deleteReport,
        modifier = modifier,
    )
}

/** Signature for posting a report: type + optional plate / street / side. */
typealias OnReport = (ReportType, String?, String?, String?) -> Unit

/** Stateless driving HUD — renders one [DriveUiState]. */
@Composable
fun DriveScreen(
    state: DriveUiState,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onStopNavigation: () -> Unit,
    onReport: OnReport,
    isAdmin: Boolean = false,
    onDeleteReport: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    var satellite by remember { mutableStateOf(true) }
    var following by remember { mutableStateOf(true) }
    var tripOptionsOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val topMode = when {
        state.guidance != null -> TopMode.Guidance
        state.trip == null -> TopMode.Search
        else -> TopMode.None
    }

    Box(modifier = modifier.fillMaxSize()) {
        DriveMap(
            location = state.location,
            radars = state.radars,
            reports = state.reports,
            zones = state.zones,
            liveUsers = state.liveUsers,
            routePoints = state.routePoints,
            satellite = satellite,
            following = following,
            onUserGesture = { following = false },
            onReportTap = if (isAdmin) ({ id -> pendingDelete = id }) else null,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Crossfade(
                targetState = topMode,
                modifier = Modifier.weight(1f),
                label = "hudTop",
            ) { mode ->
                when (mode) {
                    TopMode.Guidance -> state.guidance?.let {
                        GuidanceBanner(instruction = it, modifier = Modifier.fillMaxWidth())
                    }
                    TopMode.Search -> HudSearchBar(onClick = onOpenSearch, modifier = Modifier.fillMaxWidth())
                    TopMode.None -> Unit
                }
            }

            // While navigating, offer a one-tap stop.
            AnimatedVisibility(visible = state.trip != null) {
                XRadarIconButton(
                    icon = XRadarIcons.Close,
                    contentDescription = "Arrêter la navigation",
                    onClick = onStopNavigation,
                    tint = colors.textPrimary,
                    background = colors.surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colors.border),
                    size = 48.dp,
                )
            }

            // Settings live at the top-right.
            XRadarIconButton(
                icon = XRadarIcons.Gear,
                contentDescription = "Réglages",
                onClick = onOpenSettings,
                tint = colors.textPrimary,
                background = colors.surface.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, colors.border),
                size = 48.dp,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            AnimatedVisibility(
                visible = state.alert != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                state.alert?.let { AlertSheet(it) }
            }

            // ETA pill: Min. restantes · Distance · Arrivée (only while navigating).
            AnimatedVisibility(visible = state.trip != null) {
                state.trip?.let { TripStrip(trip = it, modifier = Modifier.fillMaxWidth()) }
            }

            // Bottom row: current speed/limit on the left, the Options control on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                SpeedPanel(
                    speedKmh = state.speedKmh,
                    status = state.speedStatus,
                    searching = state.isSearchingGps,
                    limitKmh = state.speedLimitKmh,
                )
                Spacer(Modifier.weight(1f))
                OptionButton(onClick = { tripOptionsOpen = true })
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            AnimatedVisibility(visible = !following) {
                XRadarIconButton(
                    icon = XRadarIcons.Gps,
                    contentDescription = "Recentrer",
                    onClick = { following = true },
                    tint = colors.accent,
                    background = colors.surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colors.border),
                    size = 48.dp,
                )
            }
            XRadarIconButton(
                icon = XRadarIcons.Layers,
                contentDescription = "Type de carte",
                onClick = { satellite = !satellite },
                tint = colors.textPrimary,
                background = colors.surface.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, colors.border),
                size = 48.dp,
            )
            // Primary crowdsourcing action: signal something on the road.
            XRadarIconButton(
                icon = XRadarIcons.Warning,
                contentDescription = "Signaler",
                onClick = { reportOpen = true },
                tint = colors.hazard,
                background = colors.surface.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, colors.border),
                size = 56.dp,
            )
        }

        if (tripOptionsOpen) {
            TripOptionsSheet(trip = state.trip, onDismiss = { tripOptionsOpen = false })
        }

        if (reportOpen) {
            ReportSheet(
                onReport = { type, plate, street, side ->
                    onReport(type, plate, street, side)
                    reportOpen = false
                },
                onDismiss = { reportOpen = false },
            )
        }

        pendingDelete?.let { id ->
            DeleteConfirm(
                onCancel = { pendingDelete = null },
                onConfirm = { onDeleteReport(id); pendingDelete = null },
            )
        }
    }
}

@Composable
private fun DeleteConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        XRadarSurface(
            modifier = Modifier.padding(XRadarTheme.spacing.xxl),
            shape = XRadarTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier.padding(XRadarTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
            ) {
                XRadarText("Supprimer ce signalement ?", style = XRadarTheme.typography.headline, color = colors.textPrimary)
                XRadarText("Modération admin — action définitive.", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm)) {
                    Box(
                        Modifier.weight(1f).clip(XRadarTheme.shapes.lg).border(1.dp, colors.border, XRadarTheme.shapes.lg)
                            .clickable(onClick = onCancel).padding(vertical = XRadarTheme.spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { XRadarText("Annuler", style = XRadarTheme.typography.bodyStrong, color = colors.textPrimary) }
                    Box(
                        Modifier.weight(1f).clip(XRadarTheme.shapes.lg).background(colors.hazard)
                            .clickable(onClick = onConfirm).padding(vertical = XRadarTheme.spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { XRadarText("Supprimer", style = XRadarTheme.typography.bodyStrong, color = colors.onAccent) }
                }
            }
        }
    }
}

private enum class TopMode { Search, Guidance, None }

/** Options control (bottom-right): tap opens the Waze-style trip/options sheet. */
@Composable
private fun OptionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    XRadarSurface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        shape = XRadarTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = XRadarTheme.elevation.level2,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = XRadarTheme.spacing.lg,
                vertical = XRadarTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
        ) {
            XRadarIcon(XRadarIcons.Settings, contentDescription = null, tint = colors.textSecondary, size = 22.dp)
            XRadarText("Options", style = XRadarTheme.typography.bodyStrong, color = colors.textPrimary)
        }
    }
}

@Composable
private fun HudSearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val interaction = remember { MutableInteractionSource() }
    XRadarSurface(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = XRadarTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = XRadarTheme.elevation.level2,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = XRadarTheme.spacing.lg,
                vertical = XRadarTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
        ) {
            XRadarIcon(XRadarIcons.Search, contentDescription = null, tint = colors.textSecondary, size = 20.dp)
            XRadarText(
                "Où allez-vous ?",
                style = XRadarTheme.typography.body,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(name = "HUD · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun DriveScreenPreview() {
    XRadarTheme(darkTheme = true) {
        DriveScreen(
            onOpenSearch = {},
            onOpenSettings = {},
            onStopNavigation = {},
            onReport = { _, _, _, _ -> },
            state = DriveUiState(
                speedKmh = 128,
                speedLimitKmh = 130,
                trip = TripInfo("08:42", "12,4 km", "20:14"),
                alert = RoadAlert(
                    type = AlertType.RadarFixed,
                    title = "Radar fixe",
                    roadLabel = "Autoroute A7",
                    speedLimitKmh = 130,
                    distanceMeters = 300,
                    etaSeconds = 9,
                    confidence = 0.92f,
                    lastReportedLabel = null,
                ),
                gpsSignal = GpsSignal.Good,
            ),
        )
    }
}
