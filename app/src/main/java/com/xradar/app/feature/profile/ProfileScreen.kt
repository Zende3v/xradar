package com.xradar.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
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
fun ProfileRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val stats = remember { TripHistoryRepository(context).stats() }
    ProfileScreen(account = account, stats = stats, onBack = onBack)
}

@Composable
fun ProfileScreen(
    account: Account?,
    stats: TripStats,
    onBack: () -> Unit,
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
    XRadarScreenScaffold(title = "Mon compte", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Header(account, onPickAvatar)
            AccessCard(account)

            if (account?.email != null && account?.emailVerified == false) {
                VerifyEmailCard()
            }

            if (account?.role == Role.Guest) {
                GuestNote()
            }

            XRadarListGroup {
                XRadarListRow(
                    title = "Version",
                    trailing = {
                        XRadarText("0.1.0", style = XRadarTheme.typography.callout, color = XRadarTheme.colors.textTertiary)
                    },
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

/** Status of the account: trial with its end, membership with its end, or restricted. */
@Composable
private fun AccessCard(account: Account?) {
    val colors = XRadarTheme.colors
    XRadarCard {
        Column(verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.xs)) {
            XRadarText("Statut", style = XRadarTheme.typography.caption, color = colors.textTertiary)
            XRadarText(
                com.xradar.app.feature.menu.accessLabel(account),
                style = XRadarTheme.typography.headline,
                color = colors.textPrimary,
            )
            if (account?.isRestricted == true) {
                XRadarText(
                    "La carte reste disponible ; la navigation et les signalements reviennent avec un abonnement.",
                    style = XRadarTheme.typography.subhead,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun VerifyEmailCard() {
    val colors = XRadarTheme.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var msg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    XRadarCard {
        Column(verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm)) {
            XRadarText("Email non vérifié", style = XRadarTheme.typography.headline, color = colors.textPrimary)
            XRadarText("Entre le code reçu par email.", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(colors.surface, XRadarTheme.shapes.md)
                    .border(1.dp, colors.border, XRadarTheme.shapes.md)
                    .padding(horizontal = XRadarTheme.spacing.md, vertical = XRadarTheme.spacing.md),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    singleLine = true,
                    textStyle = XRadarTheme.typography.body.merge(androidx.compose.ui.text.TextStyle(color = colors.textPrimary)),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> if (code.isEmpty()) XRadarText("Code à 6 chiffres", style = XRadarTheme.typography.body, color = colors.textTertiary); inner() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm)) {
                Box(
                    Modifier.weight(1f).clip(XRadarTheme.shapes.lg).background(colors.accent)
                        .clickable { scope.launch { msg = AccountRepository.verifyEmail(code) ?: "Email vérifié ✓" } }
                        .padding(vertical = XRadarTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) { XRadarText("Vérifier", style = XRadarTheme.typography.bodyStrong, color = colors.onAccent) }
                Box(
                    Modifier.weight(1f).clip(XRadarTheme.shapes.lg).border(1.dp, colors.border, XRadarTheme.shapes.lg)
                        .clickable { scope.launch { AccountRepository.resendVerify(); msg = "Code renvoyé." } }
                        .padding(vertical = XRadarTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) { XRadarText("Renvoyer", style = XRadarTheme.typography.bodyStrong, color = colors.textPrimary) }
            }
            msg?.let { XRadarText(it, style = XRadarTheme.typography.footnote, color = colors.accent) }
        }
    }
}

@Composable
private fun GuestNote() {
    XRadarCard {
        XRadarText(
            "Compte invité : 7 jours d'essai gratuit, puis la navigation est réservée aux membres.",
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
        )
    }
}
