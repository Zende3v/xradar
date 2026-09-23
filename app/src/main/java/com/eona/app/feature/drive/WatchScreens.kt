package com.eona.app.feature.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.trips.FollowedTrip
import com.eona.app.data.trips.ObservedGroup
import com.eona.app.data.trips.TripGroupApi
import com.eona.app.data.trips.TripShareApi
import com.eona.app.designsystem.component.EonaIconButton
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.component.WatchDot
import com.eona.app.feature.drive.component.WatchLine
import com.eona.app.feature.drive.component.WatchMap
import com.eona.app.feature.drive.group.GroupMemberRow
import com.eona.app.feature.drive.group.GroupPalette
import com.eona.app.feature.drive.group.GroupRankingView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The other side of "Partager mon trajet": someone opened a link, and follows the driver on the map
 * — where they are, the route they follow, what is left and when they arrive. It refreshes on its
 * own and stops once the share is over.
 */
@Composable
fun FollowTripScreen(shareToken: String, onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val accent = colors.accent
    var trip by remember { mutableStateOf<FollowedTrip?>(null) }
    var over by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    LaunchedEffect(shareToken) {
        val api = TripShareApi()
        // Asks again while the screen is open: the driver moves every ten seconds or so.
        while (true) {
            val seen = api.follow(shareToken, AccountRepository.token)
            if (seen == null) {
                over = true
                return@LaunchedEffect
            }
            trip = seen
            if (seen.arrived) {
                // Arrived: the map stays in place, nothing more is asked.
                over = true
                return@LaunchedEffect
            }
            delay(8_000)
        }
    }
    val current = trip
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        WatchMap(
            lines = current?.route?.let { listOf(WatchLine(it, accentArgb(accent))) } ?: emptyList(),
            dots = current?.position?.let { listOf(WatchDot(it.lat, it.lon, accentArgb(accent))) } ?: emptyList(),
            destination = current?.destination,
            modifier = Modifier.fillMaxSize(),
        )
        WatchHeader(
            title = when {
                current == null -> if (over) "Partage terminé" else "Trajet partagé"
                current.arrived -> "${current.name} est arrivé"
                else -> "${current.name} est en route"
            },
            detail = when {
                current == null || current.arrived -> if (over) "Le lien ne montre plus rien." else "En attente de sa position…"
                else -> listOfNotNull(
                    current.toLabel?.let { "Vers $it" },
                    current.remainingMeters?.let { m ->
                        if (m < 1000) "$m m restants" else String.format(Locale.FRANCE, "%.1f km restants", m / 1000.0)
                    },
                    current.etaAtMs?.let { "arrivée " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(it)) },
                ).joinToString(" · ").ifEmpty { "Trajet en cours" }
            },
            note = if (over) "Ce partage est terminé." else null,
            onClose = onClose,
        )
    }
}

/**
 * The other side of a group link: someone watches the trip without driving in it. A participant who
 * does not share, or who is not visible from the link, is simply absent. Nothing about a past trip
 * is shown, ever — only what is happening.
 */
@Composable
fun GroupWatchScreen(linkToken: String, onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var group by remember { mutableStateOf<ObservedGroup?>(null) }
    var over by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    LaunchedEffect(linkToken) {
        val api = TripGroupApi()
        while (true) {
            val seen = api.watch(linkToken, AccountRepository.token)
            if (seen == null) {
                over = true
                return@LaunchedEffect
            }
            group = seen
            if (seen.isOver) {
                // Over: the ranking stays on screen, and nothing more is asked for.
                over = true
                return@LaunchedEffect
            }
            delay(5_000)
        }
    }
    val current = group
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        WatchMap(
            lines = current?.let { g -> g.routes.mapIndexed { i, route -> WatchLine(route, GroupPalette.argb(i)) } } ?: emptyList(),
            dots = current?.members?.mapIndexedNotNull { i, m ->
                m.position?.let { WatchDot(it.lat, it.lon, GroupPalette.argb(i), faded = !m.online) }
            } ?: emptyList(),
            destination = current?.destination,
            modifier = Modifier.fillMaxSize(),
        )
        WatchHeader(
            title = current?.toLabel ?: "Trajet en groupe",
            detail = when {
                current == null -> if (over) "Ce partage est terminé." else "En attente du groupe…"
                current.isCancelled -> "Trajet annulé par le groupe"
                current.isOver -> "Trajet terminé"
                current.members.size == 1 -> "1 participant partage sa position"
                else -> "${current.members.size} participants partagent leur position"
            },
            note = null,
            onClose = onClose,
        )
        if (current != null && !current.isCancelled && (current.members.isNotEmpty() || current.ranking.isNotEmpty())) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(spacing.lg)
                    .fillMaxWidth()
                    .clip(EonaTheme.shapes.lg)
                    .background(colors.surface.copy(alpha = 0.92f))
                    .border(1.dp, colors.border, EonaTheme.shapes.lg)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (current.isOver) {
                    GroupRankingView(current.ranking)
                } else {
                    current.members.forEachIndexed { i, member -> GroupMemberRow(member, GroupPalette.color(i)) }
                }
            }
        }
    }
}

@Composable
private fun WatchHeader(title: String, detail: String, note: String?, onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .fillMaxWidth()
            .clip(EonaTheme.shapes.lg)
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.border, EonaTheme.shapes.lg)
            .padding(spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText(title, style = EonaTheme.typography.headline, color = colors.textPrimary, maxLines = 1)
            EonaText(detail, style = EonaTheme.typography.footnote, color = colors.textSecondary)
            note?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.textTertiary) }
        }
        EonaIconButton(
            icon = EonaIcons.Close,
            contentDescription = "Fermer",
            onClick = onClose,
            tint = colors.textPrimary,
            background = colors.surface.copy(alpha = 0.62f),
            border = BorderStroke(1.dp, colors.border),
            size = 40.dp,
        )
    }
}

private fun accentArgb(color: androidx.compose.ui.graphics.Color): Int =
    (0xFF shl 24) or ((color.red * 255).toInt() shl 16) or ((color.green * 255).toInt() shl 8) or (color.blue * 255).toInt()
