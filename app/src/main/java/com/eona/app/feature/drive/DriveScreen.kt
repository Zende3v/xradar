package com.eona.app.feature.drive

import com.eona.app.core.model.Account
import com.eona.app.core.model.DailyLimits
import com.eona.app.feature.subscription.OffersPrompt
import com.eona.app.feature.subscription.OffersSheet
import com.eona.app.feature.subscription.PaywallReason
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.zIndex
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eona.app.core.model.GpsSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.eona.app.R
import com.eona.app.core.model.AlertType
import com.eona.app.core.model.ReportType
import com.eona.app.core.model.RoadAlert
import com.eona.app.core.model.TripInfo
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.routing.ActiveTripRepository
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaIconButton
import com.eona.app.designsystem.component.EonaSurface
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.component.AlertStack
import com.eona.app.feature.drive.group.GroupFinishCard
import com.eona.app.feature.drive.group.GroupNoticeBanner
import com.eona.app.feature.drive.group.GroupSession
import com.eona.app.feature.drive.group.GroupStrip
import com.eona.app.feature.drive.group.MemberCardSheet
import com.eona.app.feature.drive.group.MemberCardTarget
import com.eona.app.designsystem.component.EonaConfirmDialog
import com.eona.app.feature.drive.group.GroupPalette
import com.eona.app.feature.drive.component.AudioOption
import com.eona.app.feature.drive.component.AudioOptionBar
import com.eona.app.feature.drive.component.audioMakesWay
import com.eona.app.feature.drive.component.key
import com.eona.app.feature.drive.component.DriveDock
import com.eona.app.feature.drive.component.DriveMap
import com.eona.app.feature.drive.component.GuidanceBanner
import com.eona.app.feature.drive.component.MusicBanner

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
    val account by com.eona.app.data.account.AccountRepository.account.collectAsStateWithLifecycle()
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
        isAdmin = account?.role == com.eona.app.core.model.Role.Admin,
        restricted = account?.isRestricted == true,
        isGuest = account?.role == com.eona.app.core.model.Role.Guest,
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
        onSlowdownAnswer = viewModel::answerSlowdown,
        onDismissArrival = viewModel::dismissArrival,
        group = viewModel.group,
        tripUnderway = viewModel.tripUnderway.collectAsStateWithLifecycle().value,
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
    /** "Ralentissement du trafic ?" answered: yes or no. */
    onSlowdownAnswer: (Boolean) -> Unit = {},
    /** The driver closed the arrival card. */
    onDismissArrival: () -> Unit = {},
    /** "Partager mon trajet" and "Trajet en groupe"; null in previews. */
    group: GroupSession? = null,
    /** The driver has really been on the route: a link can be opened. */
    tripUnderway: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var following by remember { mutableStateOf(true) }
    var reportOpen by remember { mutableStateOf(false) }
    var limitReportOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var dockOpen by remember { mutableStateOf(false) }
    /** Which audio bar is open, if any: only one at a time, and it hides its neighbours. */
    var audioMenu by remember { mutableStateOf<AudioMenu?>(null) }
    var shareOpen by remember { mutableStateOf(false) }
    /** A group member's card, opened from their photo (strip, map or list). */
    var card by remember { mutableStateOf<MemberCardTarget?>(null) }
    /** Stopping the navigation during a group trip is leaving the group: asked first. */
    var confirmStop by remember { mutableStateOf(false) }
    val groupState = group?.group?.collectAsStateWithLifecycle()
    val currentGroup = groupState?.value
    val groupLive = currentGroup?.isLive == true
    val groupChips = group?.chips?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val groupFocus = group?.focus?.collectAsStateWithLifecycle()?.value
    val groupNotice = group?.notice?.collectAsStateWithLifecycle()?.value
    val finishedGroup = group?.finished?.collectAsStateWithLifecycle()?.value
    val tripShare = group?.share?.collectAsStateWithLifecycle()?.value
    val groupSharing = group?.sharing?.collectAsStateWithLifecycle()?.value ?: true
    val destination = ActiveTripRepository.destination.collectAsStateWithLifecycle().value
    val myId = account?.id

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
            traffic = state.traffic,
            following = following,
            onUserGesture = { following = false },
            onReportTap = if (isAdmin) ({ id -> pendingDelete = id }) else null,
            modifier = Modifier.fillMaxSize(),
            speedLimitKmh = state.speedLimitKmh,
            group = group?.mapLayer,
            onMemberTap = group?.let { session -> { id: String -> card = session.cardTarget(id) } },
        )

        // An open audio bar closes as soon as the driver touches anywhere else.
        if (audioMenu != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { audioMenu = null },
            )
        }

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
                    EonaIconButton(
                        icon = EonaIcons.Close,
                        contentDescription = "Arrêter la navigation",
                        onClick = { if (groupLive) confirmStop = true else onStopNavigation() },
                        tint = colors.textPrimary,
                        background = colors.surface.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, colors.border),
                        size = 48.dp,
                    )
                }

                // The menu lives at the top-right, beside the search bar.
                EonaIconButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_menu),
                    contentDescription = "Menu",
                    onClick = onOpenSettings,
                    tint = colors.textPrimary,
                    background = colors.surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colors.border),
                    size = 48.dp,
                )
            }

            // A switch to a faster route, for a few seconds, under the guidance.
            val lastNotice = remember { mutableStateOf<FasterRouteNotice?>(null) }
            LaunchedEffect(state.fasterNotice) { state.fasterNotice?.let { lastNotice.value = it } }
            AnimatedVisibility(
                visible = state.fasterNotice != null,
                enter = slideInVertically { -it / 2 } + fadeIn(),
                exit = slideOutVertically { -it / 2 } + fadeOut(),
            ) {
                lastNotice.value?.let { FasterRouteBanner(it, Modifier.padding(top = spacing.sm)) }
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

            // A word about the group: someone joined, left, arrived…
            val lastNoticeText = remember { mutableStateOf<String?>(null) }
            LaunchedEffect(groupNotice) { groupNotice?.let { lastNoticeText.value = it } }
            AnimatedVisibility(
                visible = groupNotice != null,
                enter = slideInVertically { -it / 2 } + fadeIn(),
                exit = slideOutVertically { -it / 2 } + fadeOut(),
            ) {
                lastNoticeText.value?.let { text ->
                    GroupNoticeBanner(text, onDismiss = { group?.acknowledgeNotice() }, modifier = Modifier.padding(top = spacing.sm))
                }
            }

            // The others of the group, one chip each: the photo opens their card, the name follows them.
            AnimatedVisibility(
                visible = groupLive && groupChips.isNotEmpty(),
                enter = slideInVertically { -it / 2 } + fadeIn(),
                exit = slideOutVertically { -it / 2 } + fadeOut(),
            ) {
                GroupStrip(
                    chips = groupChips,
                    focus = groupFocus,
                    onOverview = {
                        following = false
                        group?.showEveryone()
                    },
                    onFocus = { id ->
                        group?.focusOn(id)
                        following = true
                    },
                    onCard = { id -> group?.let { card = it.cardTarget(id) } },
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

            AnimatedVisibility(
                visible = state.slowdownPrompt != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                SlowdownPromptCard(onAnswer = onSlowdownAnswer)
            }

            // Destination reached: the trip's figures, then the HUD is simply driving again.
            val lastArrival = remember { mutableStateOf<TripArrival?>(null) }
            LaunchedEffect(state.arrival) { state.arrival?.let { lastArrival.value = it } }
            AnimatedVisibility(
                visible = state.arrival != null,
                enter = slideInVertically { it / 2 } + fadeIn() + scaleIn(initialScale = 0.92f),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                lastArrival.value?.let { ArrivalCard(it, onDismiss = onDismissArrival) }
            }

            // The group trip is over: its ranking, until the driver closes it.
            AnimatedVisibility(
                visible = finishedGroup != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                finishedGroup?.let { GroupFinishCard(it, myId, onDismiss = { group?.dismiss() }) }
            }

            AnimatedVisibility(visible = state.routeError) {
                EonaSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = EonaTheme.shapes.lg,
                    color = colors.surface.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, colors.hazard),
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        EonaIcon(EonaIcons.Warning, contentDescription = null, tint = colors.hazard, size = 20.dp)
                        EonaText(
                            "Itinéraire indisponible — vérifie la connexion et réessaie.",
                            style = EonaTheme.typography.subhead,
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
                // Each audio bar keeps a button's width in the row and grows to the right over its
                // neighbours, which fade where they stand: nothing slides, nothing jumps.
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Box(Modifier.width(AUDIO_BUTTON_SIZE).zIndex(if (audioMenu == AudioMenu.Sound) 1f else 0f)) {
                        AlertSoundBar(
                            open = audioMenu == AudioMenu.Sound,
                            onOpenChange = { audioMenu = if (it) AudioMenu.Sound else null },
                        )
                    }
                    Box(
                        Modifier
                            .width(AUDIO_BUTTON_SIZE)
                            .zIndex(if (audioMenu == AudioMenu.Voice) 1f else 0f)
                            .audioMakesWay(audioMenu == AudioMenu.Sound),
                    ) {
                        VoiceBar(
                            open = audioMenu == AudioMenu.Voice,
                            onOpenChange = { audioMenu = if (it) AudioMenu.Voice else null },
                        )
                    }
                    if (group != null) {
                        // Always there, trip or not: a group is joined before leaving home.
                        HudRoundButton(
                            icon = ImageVector.vectorResource(R.drawable.ic_line_share),
                            description = "Partager mon trajet",
                            dot = if (tripShare != null) colors.accent else null,
                            modifier = Modifier.audioMakesWay(audioMenu != null),
                            onClick = { shareOpen = true },
                        )
                        // The group, its code, what I share; the dot says whether my position goes out.
                        if (currentGroup != null) {
                            HudRoundButton(
                                icon = EonaIcons.People,
                                description = "Trajet en groupe",
                                dot = if (groupSharing) colors.accent else colors.textTertiary,
                                modifier = Modifier.audioMakesWay(audioMenu != null),
                                onClick = { shareOpen = true },
                            )
                        }
                    }
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
                    EonaIconButton(
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
                EonaIconButton(
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
                EonaIconButton(
                    icon = EonaIcons.Warning,
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


        if (shareOpen && group != null) {
            TripShareSheet(
                session = group,
                tripUnderway = tripUnderway,
                destinationName = destination?.name,
                hasDestination = destination != null,
                myId = myId,
                onCard = { card = it },
                onDismiss = { shareOpen = false },
            )
        }

        card?.let { target ->
            if (group != null) {
                MemberCardSheet(
                    session = group,
                    target = target,
                    myId = myId,
                    chips = groupChips,
                    onDismiss = { card = null },
                    onFollow = { id ->
                        card = null
                        shareOpen = false
                        group.focusOn(id)
                        following = true
                    },
                )
            }
        }

        if (confirmStop) {
            EonaConfirmDialog(
                title = "Arrêter la navigation ?",
                message = "Tu quitteras aussi le trajet en groupe. Les autres continuent sans toi.",
                confirmLabel = "Arrêter et quitter",
                onCancel = { confirmStop = false },
                onConfirm = {
                    confirmStop = false
                    onStopNavigation()
                },
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

/** A round glass button of the HUD's audio row, with a small dot in its corner when [dot] is set. */
@Composable
private fun HudRoundButton(
    icon: ImageVector,
    description: String,
    dot: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EonaTheme.colors
    Box(modifier = modifier) {
        EonaIconButton(
            icon = icon,
            contentDescription = description,
            onClick = onClick,
            tint = colors.textPrimary,
            background = colors.surface.copy(alpha = 0.62f),
            border = BorderStroke(1.dp, colors.border),
            size = AUDIO_BUTTON_SIZE,
        )
        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
    }
}

/** Which of the two audio bars is open. */
private enum class AudioMenu { Sound, Voice }

/** What the alert-sound bar offers, in order. */
private enum class AlertSoundMode { Silent, Sound, SoundAndBuzz }

/** Alert sound: silent, sound, sound and vibration — the three laid side by side once open. */
@Composable
private fun AlertSoundBar(open: Boolean, onOpenChange: (Boolean) -> Unit) {
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    val mode = when {
        !prefs.sound -> AlertSoundMode.Silent
        prefs.vibration -> AlertSoundMode.SoundAndBuzz
        else -> AlertSoundMode.Sound
    }
    AudioOptionBar(
        options = listOf(
            AudioOption(AlertSoundMode.Silent, R.drawable.ic_bell_off, "Silencieux"),
            AudioOption(AlertSoundMode.Sound, R.drawable.ic_bell, "Son"),
            AudioOption(AlertSoundMode.SoundAndBuzz, R.drawable.ic_bell_ringing, "Son et vibration"),
        ),
        selected = mode,
        label = "Son des alertes",
        open = open,
        onOpenChange = onOpenChange,
        onPick = { picked ->
            AppPreferences.updateAlerts {
                it.copy(sound = picked != AlertSoundMode.Silent, vibration = picked == AlertSoundMode.SoundAndBuzz)
            }
        },
        modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
        size = AUDIO_BUTTON_SIZE,
    )
}

/** Spoken guidance and alert announcements, off or on. */
@Composable
private fun VoiceBar(open: Boolean, onOpenChange: (Boolean) -> Unit) {
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    AudioOptionBar(
        options = listOf(
            AudioOption(false, R.drawable.ic_volume_off, "Voix coupée"),
            AudioOption(true, R.drawable.ic_volume_on, "Voix activée"),
        ),
        selected = prefs.voice,
        label = "Annonces vocales",
        open = open,
        onOpenChange = onOpenChange,
        onPick = { voice -> AppPreferences.updateAlerts { it.copy(voice = voice) } },
        modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
        size = AUDIO_BUTTON_SIZE,
    )
}

private val AUDIO_BUTTON_SIZE = 48.dp

@Composable
private fun DeleteConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        EonaSurface(
            modifier = Modifier.padding(EonaTheme.spacing.xxl),
            shape = EonaTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier.padding(EonaTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
            ) {
                EonaText("Supprimer ce signalement ?", style = EonaTheme.typography.headline, color = colors.textPrimary)
                EonaText("Modération admin — action définitive.", style = EonaTheme.typography.subhead, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
                    Box(
                        Modifier.weight(1f).clip(EonaTheme.shapes.lg).border(1.dp, colors.border, EonaTheme.shapes.lg)
                            .clickable(onClick = onCancel).padding(vertical = EonaTheme.spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { EonaText("Annuler", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary) }
                    Box(
                        Modifier.weight(1f).clip(EonaTheme.shapes.lg).background(colors.hazard)
                            .clickable(onClick = onConfirm).padding(vertical = EonaTheme.spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { EonaText("Supprimer", style = EonaTheme.typography.bodyStrong, color = colors.onAccent) }
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
    val colors = EonaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    EonaSurface(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = EonaTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = EonaTheme.spacing.lg,
                vertical = EonaTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
        ) {
            EonaIcon(EonaIcons.Search, contentDescription = null, tint = colors.textSecondary, size = 20.dp)
            EonaText(
                "Où allez-vous ?",
                style = EonaTheme.typography.body,
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
    EonaTheme(darkTheme = true) {
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

/**
 * A faster way around the traffic was taken: the time it saves, for a few seconds; or the way
 * around a closed road.
 */
@Composable
private fun FasterRouteBanner(notice: FasterRouteNotice, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.lg)
            .background(colors.surface.copy(alpha = 0.9f))
            .border(1.dp, colors.success, EonaTheme.shapes.lg)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        EonaIcon(EonaIcons.Navigation, contentDescription = null, tint = colors.success, size = 20.dp)
        Column {
            EonaText(
                if (notice.closedRoad) "Route fermée devant" else "Itinéraire plus rapide",
                style = EonaTheme.typography.bodyStrong,
                color = colors.textPrimary,
            )
            EonaText(
                when {
                    notice.closedRoad -> "Nouvel itinéraire pour la contourner"
                    notice.gainMinutes > 1 -> "${notice.gainMinutes} min gagnées avec le trafic"
                    else -> "1 min gagnée avec le trafic"
                },
                style = EonaTheme.typography.footnote,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * The destination is reached: a round check that lands with a bounce, the place, and what the trip
 * came to. It goes on its own after a few seconds, or on "Terminé".
 */
@Composable
private fun ArrivalCard(arrival: TripArrival, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    // The check lands: it grows past its size, then settles.
    val scale = remember(arrival.id) { Animatable(0.4f) }
    LaunchedEffect(arrival.id) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    EonaSurface(
        modifier = modifier.fillMaxWidth(),
        shape = EonaTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, colors.success),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                Box(
                    modifier = Modifier
                        .scale(scale.value)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.success.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    EonaIcon(EonaIcons.Check, contentDescription = null, tint = colors.success, size = 26.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EonaText("Vous êtes arrivé", style = EonaTheme.typography.headline, color = colors.textPrimary)
                    EonaText(
                        arrival.toLabel,
                        style = EonaTheme.typography.footnote,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
                ArrivalFigure("Durée", arrivalDuration(arrival.durationSeconds))
                ArrivalFigure("Distance", arrivalDistance(arrival.distanceMeters))
                if (arrival.alertsCount > 0) {
                    ArrivalFigure("Alertes", arrival.alertsCount.toString())
                }
            }
            EonaButton(
                text = "Terminé",
                onClick = onDismiss,
                variant = EonaButtonVariant.Secondary,
                fillWidth = true,
            )
        }
    }
}

@Composable
private fun ArrivalFigure(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        EonaText(value, style = EonaTheme.typography.bodyStrong, color = EonaTheme.colors.textPrimary)
        EonaText(label, style = EonaTheme.typography.footnote, color = EonaTheme.colors.textTertiary)
    }
}

/** "8 min", "1 h 05" — the way a driver reads a trip. */
private fun arrivalDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
}

/** "820 m", "12,4 km". */
private fun arrivalDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else String.format(java.util.Locale.FRANCE, "%.1f km", meters / 1000.0)

/**
 * "Ralentissement du trafic ?": two large answers, readable at a glance; it goes by itself after a
 * few seconds.
 */
@Composable
private fun SlowdownPromptCard(onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    EonaSurface(
        modifier = modifier.fillMaxWidth(),
        shape = EonaTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaIcon(EonaIcons.Warning, contentDescription = null, tint = colors.warning, size = 22.dp)
                EonaText("Ralentissement du trafic ?", style = EonaTheme.typography.headline, color = colors.textPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaButton(
                    text = "Non",
                    onClick = { onAnswer(false) },
                    variant = EonaButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                )
                EonaButton(
                    text = "Oui",
                    onClick = { onAnswer(true) },
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                )
            }
        }
    }
}
