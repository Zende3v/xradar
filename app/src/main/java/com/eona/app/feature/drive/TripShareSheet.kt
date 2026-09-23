package com.eona.app.feature.drive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.R
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaGlowTile
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.group.GroupPanel
import com.eona.app.feature.drive.group.GroupSession
import com.eona.app.feature.drive.group.MemberCardTarget
import com.eona.app.feature.drive.group.SheetCard
import com.eona.app.feature.drive.group.shareText
import kotlinx.coroutines.launch

private enum class ShareMode(val label: String) { Link("Un lien"), Group("En groupe") }

/**
 * Sharing the trip, two ways: a link somebody follows, or a group of drivers heading for the same
 * address, each on their own road.
 *
 * Nothing opens by itself. The link is created when the driver asks for it, and only once the trip
 * has really started: before that there is nothing to follow, and nothing to stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripShareSheet(
    session: GroupSession,
    tripUnderway: Boolean,
    destinationName: String?,
    hasDestination: Boolean,
    myId: String?,
    onCard: (MemberCardTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val group by session.group.collectAsStateWithLifecycle()
    // A group running, or a trip not started yet: the group side is the useful one.
    var mode by remember { mutableStateOf(if (group != null || !tripUnderway) ShareMode.Group else ShareMode.Link) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            ModePicker(mode, onPick = { mode = it })
            when (mode) {
                ShareMode.Link -> LinkSide(session, tripUnderway, onDismiss)
                ShareMode.Group -> GroupPanel(session, destinationName, hasDestination, myId, onCard)
            }
        }
    }
}

/** Two sides on one track: the chosen side lies on the accent, the other stays clear. */
@Composable
private fun ModePicker(mode: ShareMode, onPick: (ShareMode) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.5f))
            .border(1.dp, colors.border, CircleShape)
            .padding(spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        ShareMode.entries.forEach { option ->
            val on = option == mode
            val fill by animateColorAsState(if (on) colors.accent else colors.surface.copy(alpha = 0f), label = "shareMode")
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable { onPick(option) }
                    .padding(vertical = spacing.sm + 2.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EonaIcon(
                    if (option == ShareMode.Link) ImageVector.vectorResource(R.drawable.ic_line_share) else EonaIcons.People,
                    contentDescription = null,
                    tint = if (on) colors.onAccent else colors.textSecondary,
                    size = 15.dp,
                )
                EonaText(option.label, style = EonaTheme.typography.label, color = if (on) colors.onAccent else colors.textSecondary)
            }
        }
    }
}

@Composable
private fun LinkSide(session: GroupSession, tripUnderway: Boolean, onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val share by session.share.collectAsStateWithLifecycle()
    val opening by session.openingShare.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        EonaGlowTile(icon = ImageVector.vectorResource(R.drawable.ic_line_share), size = 56.dp, iconSize = 26.dp, shape = EonaTheme.shapes.lg)
        EonaText("Partager mon trajet", style = EonaTheme.typography.title, color = colors.textPrimary)
        EonaText(
            "Quelqu'un suit ta position, ton itinéraire et ton heure d'arrivée, jusqu'à ton arrivée.",
            style = EonaTheme.typography.footnote,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }

    val current = share
    when {
        current != null -> SheetCard {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.success))
                    EonaText("Partage en cours", style = EonaTheme.typography.label, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    EonaText(
                        when (current.followers) {
                            0 -> "personne ne suit"
                            1 -> "1 personne suit"
                            else -> "${current.followers} personnes suivent"
                        },
                        style = EonaTheme.typography.footnote,
                        color = colors.textTertiary,
                    )
                }
                EonaText(current.url, style = EonaTheme.typography.footnote, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                EonaButton(
                    text = "Envoyer le lien",
                    fillWidth = true,
                    onClick = { shareText(context, "Suis mon trajet sur EONA : ${current.url}") },
                )
                EonaButton(
                    text = "Arrêter le partage",
                    variant = EonaButtonVariant.Destructive,
                    fillWidth = true,
                    onClick = {
                        scope.launch {
                            session.stopSharing()
                            onClose()
                        }
                    },
                )
                EonaText(
                    "Le lien s'éteint 15 minutes après ton arrivée. Il faut un compte EONA pour l'ouvrir.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                )
            }
        }
        !tripUnderway -> SheetCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                EonaIcon(EonaIcons.Navigation, contentDescription = null, tint = colors.textTertiary, size = 22.dp)
                EonaText("Pas encore en route", style = EonaTheme.typography.label, color = colors.textPrimary)
                EonaText(
                    "Le lien se crée une fois le trajet commencé, quand tu es sur l'itinéraire. Pour partir à plusieurs dès maintenant, passe par « En groupe ».",
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            EonaButton(
                text = "Créer le lien",
                loading = opening,
                fillWidth = true,
                onClick = { scope.launch { failed = session.startSharing() == null } },
            )
            if (failed) {
                EonaText(
                    "Le lien n'a pas pu être créé. Vérifie ta connexion et réessaie.",
                    style = EonaTheme.typography.footnote,
                    color = colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
