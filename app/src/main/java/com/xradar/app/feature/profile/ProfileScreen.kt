package com.xradar.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.stats.TripHistoryRepository
import com.xradar.app.data.stats.TripStats
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

@Composable
fun ProfileRoute(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val stats = remember { TripHistoryRepository(context).stats() }
    ProfileScreen(account = account, stats = stats, onBack = onBack, onOpenSettings = onOpenSettings)
}

@Composable
fun ProfileScreen(
    account: Account?,
    stats: TripStats,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = XRadarTheme.spacing
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch { encodeAvatar(context, uri)?.let { AccountRepository.uploadAvatar(it) } }
    }
    val onPickAvatar: (() -> Unit)? = if (account?.canEditProfile == true) ({ picker.launch("image/*") }) else null
    XRadarScreenScaffold(title = "Profil", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Header(account, onPickAvatar)
            Stats(stats)

            if (account?.role == Role.Guest) {
                GuestNote()
            }

            XRadarListGroup {
                XRadarListRow(
                    title = "Paramètres",
                    leadingIcon = XRadarIcons.Settings,
                    leadingTint = XRadarTheme.colors.textSecondary,
                    onClick = onOpenSettings,
                    trailing = {
                        XRadarIcon(XRadarIcons.ChevronRight, contentDescription = null, tint = XRadarTheme.colors.textTertiary, size = 20.dp)
                    },
                )
                XRadarDivider(Modifier.padding(start = 58.dp))
                XRadarListRow(
                    title = "Version",
                    trailing = {
                        XRadarText("0.1.0", style = XRadarTheme.typography.callout, color = XRadarTheme.colors.textTertiary)
                    },
                )
            }

            XRadarListGroup {
                XRadarListRow(
                    title = if (account?.role == Role.Guest) "Changer de compte" else "Se déconnecter",
                    leadingIcon = XRadarIcons.Close,
                    leadingTint = XRadarTheme.colors.hazard,
                    onClick = { AccountRepository.logout() },
                )
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun Header(account: Account?, onPickAvatar: (() -> Unit)?) {
    val colors = XRadarTheme.colors
    val role = account?.role ?: Role.Guest
    val name = account?.displayName?.takeIf { it.isNotBlank() } ?: role.label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
    ) {
        val avatar = Modifier.let { m -> if (onPickAvatar != null) m.clickable(onClick = onPickAvatar) else m }
        Box(avatar, contentAlignment = Alignment.Center) {
            AsyncAvatar(url = account?.avatarUrl, initial = name, size = 64.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.xs)) {
            XRadarText(name, style = XRadarTheme.typography.titleLarge, color = colors.textPrimary)
            XRadarBadge(role.label, color = roleColor(role))
            if (onPickAvatar != null) {
                XRadarText("Changer la photo", style = XRadarTheme.typography.caption, color = colors.accent)
            }
        }
    }
}

@Composable
private fun roleColor(role: Role) = when (role) {
    Role.Admin -> XRadarTheme.colors.accent
    Role.Client -> XRadarTheme.colors.radarFixed
    Role.Guest -> XRadarTheme.colors.textSecondary
}

@Composable
private fun Stats(stats: TripStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
    ) {
        StatTile(stats.trips.toString(), "Trajets", Modifier.weight(1f))
        StatTile(grouped(stats.kilometers), "km", Modifier.weight(1f))
        StatTile(grouped(stats.alerts), "Alertes", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    XRadarCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.xs),
        ) {
            XRadarText(value, style = XRadarTheme.typography.title, color = XRadarTheme.colors.textPrimary)
            XRadarText(label.uppercase(), style = XRadarTheme.typography.caption, color = XRadarTheme.colors.textTertiary)
        }
    }
}

@Composable
private fun GuestNote() {
    XRadarCard {
        XRadarText(
            "Mode invité : tes trajets et stats sont enregistrés uniquement sur cet appareil.",
            style = XRadarTheme.typography.subhead,
            color = XRadarTheme.colors.textSecondary,
        )
    }
}

/** 3240 -> "3 240" (French thousands). */
private fun grouped(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

@Preview(name = "Profil · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun ProfileScreenPreview() {
    XRadarTheme(darkTheme = true) {
        ProfileScreen(
            account = Account(id = "x", role = Role.Admin, username = "Arthur", displayName = "Arthur", avatarUrl = null, email = "a@b.com", banned = false),
            stats = TripStats(trips = 128, kilometers = 3240, alerts = 512),
            onBack = {},
            onOpenSettings = {},
        )
    }
}
