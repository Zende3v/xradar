package com.eona.app.feature.drive.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eona.app.data.trips.GroupMember
import com.eona.app.data.trips.GroupMemberState
import com.eona.app.data.trips.MemberCard
import com.eona.app.data.trips.groupDistance
import com.eona.app.data.trips.groupDuration
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaLoadingState
import com.eona.app.designsystem.component.EonaMessageState
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.menu.TrustStars
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A member's card, opened from their photo — in the strip, on the map or in the group's list: who
 * they are (photo, name, status, since when), how much the others trust their reports, how they
 * drive, and where they stand in this trip. The live part follows the group's news; the rest is
 * read once and again now and then. A driver who hid their statistics shows the first part only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCardSheet(
    session: GroupSession,
    target: MemberCardTarget,
    myId: String?,
    chips: List<GroupChip>,
    onDismiss: () -> Unit,
    /** Follow them on the map; null where there is no map to follow them on. */
    onFollow: ((String) -> Unit)? = null,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var card by remember(target.id) { mutableStateOf<MemberCard?>(null) }
    var failed by remember(target.id) { mutableStateOf(false) }
    LaunchedEffect(target.id) {
        // Read at once, then again now and then while it is open: the state and the rank move.
        while (true) {
            val fresh = session.memberCard(target.id)
            if (fresh != null) card = fresh else if (card == null) failed = true
            delay(REFRESH_MS)
        }
    }
    val color = GroupPalette.color(target.colorIndex)
    val isMe = target.id == myId
    val chip = chips.firstOrNull { it.id == target.id }

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
            val shown = card
            when {
                shown != null -> {
                    Header(shown, color, isMe)
                    Trust(shown)
                    Trip(shown.live, chip?.detail, color)
                    Stats(shown, isMe, color)
                    if (onFollow != null && chip?.onMap == true && !isMe) {
                        EonaButton(
                            text = "Suivre sur la carte",
                            onClick = { onFollow(shown.id) },
                            leadingIcon = EonaIcons.Navigation,
                            fillWidth = true,
                        )
                    }
                }
                failed -> EonaMessageState(
                    icon = EonaIcons.Info,
                    title = "Fiche indisponible",
                    message = "Ce conducteur n'est plus dans le groupe, ou le réseau ne répond pas.",
                )
                else -> Box(Modifier.fillMaxWidth().height(240.dp)) { EonaLoadingState(label = "Chargement…") }
            }
        }
    }
}

@Composable
private fun Header(card: MemberCard, color: androidx.compose.ui.graphics.Color, isMe: Boolean) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        GroupAvatar(
            card.avatarUrl,
            card.name,
            color,
            96.dp,
            modifier = Modifier.shadow(16.dp, CircleShape, ambientColor = color, spotColor = color),
        )
        EonaText(
            if (isMe) "${card.name} (moi)" else card.name,
            style = EonaTheme.typography.title,
            color = colors.textPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            EonaBadge(text = card.role.label, glow = card.role != com.eona.app.core.model.Role.Guest)
            if (card.isHost) EonaBadge(text = "Mène le groupe", color = color)
        }
        card.memberSinceLabel?.let {
            EonaText(it, style = EonaTheme.typography.footnote, color = colors.textTertiary)
        }
    }
}

@Composable
private fun Trust(card: MemberCard) {
    val colors = EonaTheme.colors
    SheetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EonaText("Note de confiance", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
                EonaText("Ses signalements confirmés par les autres", style = EonaTheme.typography.footnote, color = colors.textTertiary)
            }
            TrustStars(card.trust)
        }
    }
}

/** Where they stand in this trip. */
@Composable
private fun Trip(member: GroupMember, liveDetail: String?, color: androidx.compose.ui.graphics.Color) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            EonaText("DANS CE TRAJET", style = EonaTheme.typography.caption, color = colors.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (member.state == GroupMemberState.Driving && member.online) color else colors.textTertiary),
                )
                EonaText(
                    if (member.state == GroupMemberState.Arrived) arrivedLabel(member) else liveDetail ?: member.detailLabel,
                    style = EonaTheme.typography.bodyStrong,
                    color = colors.textPrimary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                member.rank?.let { rank ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EonaIcon(EonaIcons.Trophy, contentDescription = null, tint = if (rank == 1) colors.warning else colors.textSecondary, size = 14.dp)
                        EonaText(if (rank == 1) "1er" else "${rank}e", style = EonaTheme.typography.caption, color = if (rank == 1) colors.warning else colors.textSecondary)
                    }
                }
            }
            val eta = member.etaAtMs
            if (member.sharing && member.state == GroupMemberState.Driving && eta != null) {
                EonaText(
                    "Arrivée prévue à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(eta)),
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Stats(card: MemberCard, isMe: Boolean, color: androidx.compose.ui.graphics.Color) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val stats = card.stats
    SheetCard {
        if (stats != null) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                EonaText("AU VOLANT AVEC EONA", style = EonaTheme.typography.caption, color = colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Tile(kilometers(stats.distanceMeters), "Parcourus", color, Modifier.weight(1f))
                    Tile(hours(stats.driveDurationSeconds), "Sur la route", colors.textPrimary, Modifier.weight(1f))
                    Tile(grouped(stats.tripCount), "Trajets", colors.textPrimary, Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                Figure("Signalements déclarés", grouped(stats.reportsDeclared))
                Figure("Confirmés par d'autres", grouped(stats.reportsConfirmed))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                EonaIcon(EonaIcons.EyeSlash, contentDescription = null, tint = colors.textTertiary, size = 18.dp)
                EonaText(
                    if (isMe) "Tu caches tes statistiques au groupe (Menu ▸ Confidentialité)." else "${card.name} garde ses statistiques pour lui.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier = modifier.padding(vertical = EonaTheme.spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
        EonaText(value, style = EonaTheme.typography.headline, color = tint, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        EonaText(label.uppercase(), style = EonaTheme.typography.caption, color = EonaTheme.colors.textTertiary, maxLines = 1)
    }
}

@Composable
private fun Figure(title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EonaText(title, style = EonaTheme.typography.body, color = EonaTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        EonaText(value, style = EonaTheme.typography.bodyStrong, color = EonaTheme.colors.textPrimary)
    }
}

/** A pale block on the sheet: it gathers a few lines and keeps them legible. */
@Composable
internal fun SheetCard(content: @Composable () -> Unit) {
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.xl)
            .background(colors.surface.copy(alpha = 0.6f))
            .border(1.dp, colors.border, EonaTheme.shapes.xl)
            .padding(EonaTheme.spacing.lg),
    ) { content() }
}

/** "Arrivé en 2h05 · 184 km". */
private fun arrivedLabel(member: GroupMember): String {
    val parts = mutableListOf("Arrivé" + (member.durationSeconds?.let { " en " + groupDuration(it) } ?: ""))
    member.distanceMeters?.let { parts += groupDistance(it) }
    return parts.joinToString(" · ")
}

/** "9,5 km", "3 240 km". */
private fun kilometers(meters: Int): String {
    val km = meters / 1000.0
    return if (km < 10) String.format(Locale.FRANCE, "%.1f km", km) else "${grouped(km.toInt())} km"
}

/** "45 min", "2h05", "2460h". */
private fun hours(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 600 -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
        else -> "${minutes / 60}h"
    }
}

/** 3240 → "3 240". */
private fun grouped(value: Int): String = String.format(Locale.FRANCE, "%,d", value).replace(' ', ' ').replace(' ', ' ')

private const val REFRESH_MS = 15_000L
