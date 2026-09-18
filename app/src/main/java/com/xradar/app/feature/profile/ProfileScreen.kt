package com.xradar.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.BuildConfig
import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.stats.TripHistoryRepository
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarConfirmDialog
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.subscription.OffersSheet
import com.xradar.app.feature.subscription.PaywallReason
import kotlinx.coroutines.launch

@Composable
fun ProfileRoute(onBack: () -> Unit) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    ProfileScreen(account = account, onBack = onBack)
}

/**
 * "Mon compte": name, role and photo (members change it), "Changer de pseudo" (clients with
 * access), access status, email verification, the guest's trial note, the app version, and the
 * deletion of the account.
 */
@Composable
fun ProfileScreen(
    account: Account?,
    onBack: () -> Unit,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf<PaywallReason?>(null) }
    var renaming by remember { mutableStateOf(false) }
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
            Header(account, onPickAvatar, onPhotoOffers = { offers = PaywallReason.Photo })

            // Only a client whose access runs, as the backend says; once a week.
            if (account?.canChangeUsername == true) {
                val wait = account.usernameChangeableAt
                    ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                    ?.takeIf { it.isAfter(java.time.Instant.now()) }
                    ?.let { "Prochain changement le ${com.xradar.app.feature.menu.shortDate(account.usernameChangeableAt)}" }
                XRadarListGroup {
                    XRadarListRow(
                        title = "Changer de pseudo",
                        subtitle = wait ?: "Une fois par semaine",
                        leadingIcon = XRadarIcons.User,
                        leadingTint = colors.accent,
                        onClick = if (wait == null) ({ renaming = true }) else null,
                    )
                }
            }

            AccessCard(account)

            if (account?.email != null && account.emailVerified == false) {
                VerifyEmailCard()
            }

            if (account?.role == Role.Guest) {
                GuestNote(account)
            }

            XRadarListGroup {
                XRadarListRow(
                    title = "Version",
                    trailing = {
                        XRadarText(BuildConfig.VERSION_NAME, style = XRadarTheme.typography.callout, color = colors.textTertiary)
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(XRadarTheme.shapes.lg)
                        .border(BorderStroke(1.dp, colors.danger), XRadarTheme.shapes.lg)
                        .clickable(enabled = !deleting) { confirmDelete = true }
                        .padding(vertical = spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    XRadarText(
                        if (deleting) "Suppression…" else "Supprimer mon compte",
                        style = XRadarTheme.typography.bodyStrong,
                        color = colors.danger,
                    )
                }
                deleteError?.let { XRadarText(it, style = XRadarTheme.typography.footnote, color = colors.danger) }
            }

            Spacer(Modifier.height(spacing.xxl))
        }

        offers?.let { reason -> OffersSheet(reason, account, onClose = { offers = null }) }

        if (renaming) {
            UsernameDialog(current = account?.username.orEmpty(), onClose = { renaming = false })
        }

        if (confirmDelete) {
            XRadarConfirmDialog(
                title = "Supprimer ton compte ?",
                message = "Ton compte, ta photo, tes statistiques et tes trajets sont effacés pour de bon. " +
                    "Tes signalements restent pour les autres conducteurs, sans ton nom.",
                confirmLabel = "Supprimer définitivement",
                onCancel = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    deleting = true
                    deleteError = null
                    scope.launch {
                        // On success the account is gone: the app goes back to onboarding by itself.
                        val error = AccountRepository.deleteAccount()
                        if (error == null) TripHistoryRepository(context).removeAll() else deleteError = error
                        deleting = false
                    }
                },
            )
        }
    }
}

@Composable
private fun Header(account: Account?, onPickAvatar: (() -> Unit)?, onPhotoOffers: () -> Unit) {
    val colors = XRadarTheme.colors
    val role = account?.role ?: Role.Guest
    val name = account?.displayName?.takeIf { it.isNotBlank() } ?: role.label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
    ) {
        // Members change their photo; for the others it is one of the membership's features.
        val avatar = Modifier.clickable(onClick = onPickAvatar ?: onPhotoOffers)
        Box(avatar, contentAlignment = Alignment.Center) {
            AsyncAvatar(url = account?.avatarUrl, initial = name, size = 64.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.xs)) {
            XRadarText(name, style = XRadarTheme.typography.titleLarge, color = colors.textPrimary)
            XRadarBadge(role.label, glow = true)
            XRadarText(
                if (onPickAvatar != null) "Changer la photo" else "Photo réservée aux membres",
                style = XRadarTheme.typography.caption,
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onPickAvatar ?: onPhotoOffers),
            )
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
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
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
private fun GuestNote(account: Account) {
    val reports = account.limits?.reportsPerDay ?: 5
    val trips = account.limits?.tripsPerDay ?: 7
    XRadarCard {
        XRadarText(
            "Compte invité : 7 jours d'essai gratuit, $reports signalements et $trips trajets par jour. " +
                "Ensuite, la carte seule sans abonnement.",
            style = XRadarTheme.typography.subhead,
            color = XRadarTheme.colors.textSecondary,
        )
    }
}

@Preview(name = "Profil · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun ProfileScreenPreview() {
    XRadarTheme(darkTheme = true) {
        ProfileScreen(
            account = Account(id = "x", role = Role.Admin, username = "Arthur", displayName = "Arthur", avatarUrl = null, email = "a@b.com", banned = false),
            onBack = {},
        )
    }
}
