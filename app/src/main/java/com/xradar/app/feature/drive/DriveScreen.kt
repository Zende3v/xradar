package com.xradar.app.feature.drive

import com.xradar.app.core.model.Account
import com.xradar.app.core.model.DailyLimits
import com.xradar.app.feature.subscription.OffersPrompt
import com.xradar.app.feature.subscription.OffersSheet
import com.xradar.app.feature.subscription.PaywallReason
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.compose.LifecycleStartEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.xradar.app.R
import com.xradar.app.core.model.AlertType
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.core.model.TripInfo
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.routing.ActiveTripRepository
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarIconButton
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.drive.component.AlertStack
import com.xradar.app.feature.drive.component.key
import com.xradar.app.feature.drive.component.DriveDock
import com.xradar.app.feature.drive.component.DriveMap
import com.xradar.app.feature.drive.component.GuidanceBanner
import com.xradar.app.feature.drive.component.MusicBanner

/** Entry point wired to the Phase-1 simulation. Swap the source in Phase 2. */
@Composable
fun DriveRoute(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DriveViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dismissedAlerts by viewModel.dismissedAlerts.collectAsStateWithLifecycle()
    val votedReports by viewModel.votedReports.collectAsStateWithLifecycle()
    val account by com.xradar.app.data.account.AccountRepository.account.collectAsStateWithLifecycle()
    val offers by OffersPrompt.reason.collectAsStateWithLifecycle()
    // Back from Android's settings (or anywhere else): notification access may have changed.
    LifecycleStartEffect(viewModel) {
        viewModel.onHudStarted()
        onStopOrDispose { }
    }
    DriveScreen(
        state = state,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onStopNavigation = { ActiveTripRepository.clear() },
        onReport = viewModel::report,
        isAdmin = account?.role == com.xradar.app.core.model.Role.Admin,
        restricted = account?.isRestricted == true,
        isGuest = account?.role == com.xradar.app.core.model.Role.Guest,
        limits = account?.limits,
        account = account,
        offers = offers,
        onBlocked = OffersPrompt::show,
        onCloseOffers = OffersPrompt::dismiss,
        onDeleteReport = viewModel::deleteReport,
        dismissedAlerts = dismissedAlerts,
        onDismissAlert = viewModel::dismissAlert,
        onMusic = viewModel::onMusic,
        onReportSpeedLimit = viewModel::reportSpeedLimit,
        votedReports = votedReports,
        onVote = viewModel::vote,
        modifier = modifier,
    )
}

/** Signature for posting a report — see [ReportDraft]. */
typealias OnReport = (ReportDraft) -> Unit

/** Stateless driving HUD — renders one [DriveUiState]. */
@Composable
fun DriveScreen(
    state: DriveUiState,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onStopNavigation: () -> Unit,
    onReport: OnReport,
    isAdmin: Boolean = false,
    /** Trial over / subscription lapsed: map only — no navigation, no reporting. */
    restricted: Boolean = false,
    /** Guests: no music shortcut, limits of the day ([limits]). */
    isGuest: Boolean = false,
    limits: DailyLimits? = null,
    account: Account? = null,
    /** The offers shown over the map, and why; null when hidden. */
    offers: PaywallReason? = null,
    /** A blocked action (account blocked, a guest's limit, a members' feature): the offers show. */
    onBlocked: (PaywallReason) -> Unit = {},
    onCloseOffers: () -> Unit = {},
    onDeleteReport: (String) -> Unit = {},
    /** Keys of the alerts the driver swiped away; kept off the HUD for now. */
    dismissedAlerts: Set<String> = emptySet(),
    onDismissAlert: (String) -> Unit = {},
    /** The music button and the mini-player's controls. */
    onMusic: (MusicAction) -> Unit = {},
    /** A new speed limit the driver proposes where they are (see [SpeedLimitSheet]). */
    onReportSpeedLimit: (Int) -> Unit = {},
    /** Reports the driver already voted on, and the vote itself ("toujours là" = true). */
    votedReports: Set<String> = emptySet(),
    onVote: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    var following by remember { mutableStateOf(true) }
    var reportOpen by remember { mutableStateOf(false) }
    var limitReportOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var dockOpen by remember { mutableStateOf(false) }

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
            signs = state.signs,
            routePoints = state.routePoints,
            following = following,
            onUserGesture = { following = false },
            onReportTap = if (isAdmin) ({ id -> pendingDelete = id }) else null,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        TopMode.Search -> HudSearchBar(
                            onClick = {
                                when {
                                    restricted -> onBlocked(PaywallReason.Restricted)
                                    limits?.tripsLeft() == 0 -> onBlocked(PaywallReason.TripLimit)
                                    else -> onOpenSearch()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
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

                // The menu lives at the top-right, beside the search bar.
                XRadarIconButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_menu),
                    contentDescription = "Menu",
                    onClick = onOpenSettings,
                    tint = colors.textPrimary,
                    background = colors.surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colors.border),
                    size = 48.dp,
                )
            }

            // The music banner opens under the search bar (or the guidance): in the flow, so
            // it never covers either; the alerts stay at the bottom of the screen.
            AnimatedVisibility(
                visible = state.musicOpen,
                enter = slideInVertically { -it / 2 } + fadeIn(),
                exit = slideOutVertically { -it / 2 } + fadeOut(),
            ) {
                MusicBanner(
                    state = state.media,
                    onAction = onMusic,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Alerts the driver swiped away stay off the HUD for a while (still live for the voice).
            val shownAlerts = remember(state.alerts, dismissedAlerts) {
                state.alerts.filter { it.key !in dismissedAlerts }
            }
            AnimatedVisibility(
                visible = shownAlerts.isNotEmpty() && !restricted,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                // Every live alert in one card: the nearest in full, the others one tap away;
                // a sideways swipe hides one.
                AlertStack(
                    shownAlerts,
                    onDismiss = onDismissAlert,
                    // Only crowd reports, close ahead, and one voice per driver.
                    canVote = { alert ->
                        val id = alert.id
                        id != null && alert.lastReportedLabel != null && id !in votedReports &&
                            alert.distanceMeters <= VOTE_DISTANCE_M
                    },
                    onVote = { alert, confirm -> alert.id?.let { onVote(it, confirm) } },
                )
            }

            AnimatedVisibility(visible = state.routeError) {
                XRadarSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = XRadarTheme.shapes.lg,
                    color = colors.surface.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, colors.hazard),
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        XRadarIcon(XRadarIcons.Warning, contentDescription = null, tint = colors.hazard, size = 20.dp)
                        XRadarText(
                            "Itinéraire indisponible — vérifie la connexion et réessaie.",
                            style = XRadarTheme.typography.subhead,
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            // "e1"/"e2": alert sound + vibration, then voice — reachable without
            // opening the dock, on the left so the reporting button stays on the right.
            AnimatedVisibility(
                visible = !dockOpen,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    AlertSoundButton()
                    VoiceButton()
                }
            }

            // "E3": the drop-up dock — speed + live limit, red-light timer, and the
            // options one drag away. Replaces the old ETA pill / speed / Options row.
            // Its limit sign opens the speed-limit sheet (a position is needed to report).
            val openLimitReport: () -> Unit = { if (restricted) onBlocked(PaywallReason.Restricted) else limitReportOpen = true }
            DriveDock(
                speedKmh = state.speedKmh,
                limitKmh = state.speedLimitKmh,
                status = state.speedStatus,
                searching = state.isSearchingGps,
                trip = state.trip,
                onOpenChange = { dockOpen = it },
                onLimitClick = openLimitReport.takeUnless { state.isSearchingGps },
            )
        }

        // Map controls: they step out of the way while the dock is deployed.
        AnimatedVisibility(
            visible = !dockOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
        ) {
            Column(
                modifier = Modifier.padding(end = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                AnimatedVisibility(visible = !following) {
                    XRadarIconButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_recenter),
                        contentDescription = "Recentrer",
                        onClick = { following = true },
                        tint = Color.Unspecified, // the asset carries its own colours
                        background = colors.surface.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, colors.border),
                        size = MAP_CONTROL_SIZE,
                    )
                }
                // Music: opens the mini-player under the search bar; a second tap closes it. The
                // shortcut is for members; an open banner can always be closed.
                XRadarIconButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_music),
                    contentDescription = if (state.musicOpen) "Fermer la musique" else "Musique",
                    onClick = {
                        when {
                            state.musicOpen || (!restricted && !isGuest) -> onMusic(MusicAction.ToggleBanner)
                            restricted -> onBlocked(PaywallReason.Restricted)
                            else -> onBlocked(PaywallReason.Music)
                        }
                    },
                    tint = colors.textPrimary,
                    background = colors.surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colors.border),
                    size = MAP_CONTROL_SIZE,
                )
                // Primary crowdsourcing action: signal something on the road. Neutral glass and a
                // grey triangle, like the other controls (no orange, no glow).
                XRadarIconButton(
                    icon = XRadarIcons.Warning,
                    contentDescription = "Signaler",
                    onClick = {
                        when {
                            restricted -> onBlocked(PaywallReason.Restricted)
                            limits?.reportsLeft() == 0 -> onBlocked(PaywallReason.ReportLimit)
                            else -> reportOpen = true
                        }
                    },
                    tint = colors.textSecondary,
                    background = colors.surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colors.border),
                    size = MAP_CONTROL_SIZE,
                )
            }
        }

        if (reportOpen) {
            ReportSheet(
                onReport = { draft ->
                    onReport(draft)
                    reportOpen = false
                },
                onDismiss = { reportOpen = false },
            )
        }

        if (limitReportOpen) {
            SpeedLimitSheet(
                currentKmh = state.speedLimitKmh,
                onReport = { kmh ->
                    onReportSpeedLimit(kmh)
                    limitReportOpen = false
                },
                onDismiss = { limitReportOpen = false },
            )
        }


        pendingDelete?.let { id ->
            DeleteConfirm(
                onCancel = { pendingDelete = null },
                onConfirm = { onDeleteReport(id); pendingDelete = null },
            )
        }

        offers?.let { reason -> OffersSheet(reason, account, onClose = onCloseOffers) }
    }
}

/**
 * Alert sound: off → on → on with vibration. One button, three states, so the
 * driver can silence everything with a thumb without opening a menu.
 */
@Composable
private fun AlertSoundButton() {
    val colors = XRadarTheme.colors
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    val icon = when {
        !prefs.sound -> R.drawable.ic_bell_off
        prefs.vibration -> R.drawable.ic_bell_ringing
        else -> R.drawable.ic_bell
    }
    XRadarIconButton(
        icon = ImageVector.vectorResource(icon),
        contentDescription = "Son des alertes",
        onClick = {
            AppPreferences.updateAlerts {
                when {
                    !it.sound -> it.copy(sound = true, vibration = false)
                    !it.vibration -> it.copy(vibration = true)
                    else -> it.copy(sound = false, vibration = false)
                }
            }
        },
        tint = Color.Unspecified, // the asset carries its own colours
        background = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        size = 48.dp,
    )
}

/** Spoken guidance and alert announcements, on or off. */
@Composable
private fun VoiceButton() {
    val colors = XRadarTheme.colors
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    XRadarIconButton(
        icon = ImageVector.vectorResource(
            if (prefs.voice) R.drawable.ic_volume_on else R.drawable.ic_volume_off,
        ),
        contentDescription = "Annonces vocales",
        onClick = { AppPreferences.updateAlerts { it.copy(voice = !it.voice) } },
        tint = Color.Unspecified, // the asset carries its own colours
        background = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        size = 48.dp,
    )
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

/** Recenter, music and report share one size: the report button's. */
private val MAP_CONTROL_SIZE = 56.dp

/** A report closer than this shows "toujours là / plus là". */
private const val VOTE_DISTANCE_M = 300

@Composable
private fun HudSearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val interaction = remember { MutableInteractionSource() }
    XRadarSurface(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = XRadarTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
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
    // Three alerts at once, as in central Paris: one in full, two rows beneath.
    val alerts = listOf(
        RoadAlert(
            type = AlertType.RadarFixed,
            title = "Radar fixe",
            roadLabel = "Autoroute A7",
            speedLimitKmh = 130,
            distanceMeters = 300,
            etaSeconds = 9,
            confidence = 0.92f,
            lastReportedLabel = null,
            id = "radar-1",
        ),
        RoadAlert(
            type = AlertType.ControlZone,
            title = "Zone de contrôle",
            roadLabel = "Sens opposé",
            speedLimitKmh = null,
            distanceMeters = 640,
            etaSeconds = 18,
            confidence = 0.7f,
            lastReportedLabel = "2 signalements · il y a 4 min",
            id = "report-1",
        ),
        RoadAlert(
            type = AlertType.Accident,
            title = "Accident",
            roadLabel = null,
            speedLimitKmh = null,
            distanceMeters = 1200,
            etaSeconds = 34,
            confidence = 0.85f,
            lastReportedLabel = "1 signalement · à l'instant",
            id = "report-2",
        ),
    )
    XRadarTheme(darkTheme = true) {
        DriveScreen(
            onOpenSearch = {},
            onOpenSettings = {},
            onStopNavigation = {},
            onReport = {},
            state = DriveUiState(
                speedKmh = 128,
                speedLimitKmh = 130,
                trip = TripInfo("08:42", "12,4 km", "20:14"),
                alert = alerts.first(),
                gpsSignal = GpsSignal.Good,
                alerts = alerts,
            ),
        )
    }
}
