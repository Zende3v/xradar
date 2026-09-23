package com.eona.app.feature.profile

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
import com.eona.app.BuildConfig
import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.stats.TripHistoryRepository
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaConfirmDialog
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.subscription.OffersSheet
import com.eona.app.feature.subscription.PaywallReason
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
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf<PaywallReason?>(null) }
    var renaming by remember { mutableStateOf(false) }
    // What the Google row is doing, and what it has to say.
    var linking by remember { mutableStateOf(false) }
    var linkMessage by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch { encodeAvatar(context, uri)?.let { AccountRepository.uploadAvatar(it) } }
    }
    val onPickAvatar: (() -> Unit)? = if (account?.canEditProfile == true) ({ picker.launch("image/*") }) else null
    EonaScreenScaffold(title = "Mon compte", onBack = onBack) {
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
                    ?.let { "Prochain changement le ${com.eona.app.feature.menu.shortDate(account.usernameChangeableAt)}" }
                EonaListGroup {
                    EonaListRow(
                        title = "Changer de pseudo",
                        subtitle = wait ?: "Une fois par semaine",
                        leadingIcon = EonaIcons.User,
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

            if (com.eona.app.data.account.GoogleAuth.isAvailable && account != null) {
                val linked = "google" in account.providers
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaListGroup(title = "Connexion") {
                        EonaListRow(
                            title = if (linked) "Dissocier Google" else "Lier mon compte Google",
                            subtitle = if (linked) "Ce compte peut se connecter avec Google" else "Pour entrer aussi avec Google",
                            onClick = if (linking) {
                                null
                            } else {
                                {
                                    linking = true
                                    linkMessage = null
                                    scope.launch {
                                        if (linked) {
                                            linkMessage = AccountRepository.unlinkGoogle() ?: "Compte Google dissocié."
                                            com.eona.app.data.account.GoogleAuth.signOut(context)
                                        } else {
                                            when (val result = com.eona.app.data.account.GoogleAuth.identityToken(context)) {
                                                is com.eona.app.data.account.GoogleResult.Failure ->
                                                    if (result.message.isNotEmpty()) linkMessage = result.message
                                                is com.eona.app.data.account.GoogleResult.Token ->
                                                    linkMessage = AccountRepository.linkGoogle(result.idToken) ?: "Compte Google lié."
                                            }
                                        }
                                        linking = false
                                    }
                                }
                            },
                        )
                    }
                    EonaText(
                        linkMessage ?: "Lier Google te laisse entrer d'un geste. La dissociation est refusée s'il ne te reste aucun autre moyen de te connecter.",
                        style = EonaTheme.typography.footnote,
                        color = if (linkMessage != null) colors.textSecondary else colors.textTertiary,
                        modifier = Modifier.padding(horizontal = spacing.md),
                    )
                }
            }

            EonaListGroup {
                EonaListRow(
                    title = "Version",
                    trailing = {
                        EonaText(BuildConfig.VERSION_NAME, style = EonaTheme.typography.callout, color = colors.textTertiary)
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(EonaTheme.shapes.lg)
                        .border(BorderStroke(1.dp, colors.danger), EonaTheme.shapes.lg)
                        .clickable(enabled = !deleting) { confirmDelete = true }
                        .padding(vertical = spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    EonaText(
                        if (deleting) "Suppression…" else "Supprimer mon compte",
                        style = EonaTheme.typography.bodyStrong,
                        color = colors.danger,
                    )
                }
                deleteError?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.danger) }
            }

            Spacer(Modifier.height(spacing.xxl))
        }

        offers?.let { reason -> OffersSheet(reason, account, onClose = { offers = null }) }

        if (renaming) {
            UsernameDialog(current = account?.username.orEmpty(), onClose = { renaming = false })
        }

        if (confirmDelete) {
            EonaConfirmDialog(
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
    val colors = EonaTheme.colors
    val role = account?.role ?: Role.Guest
    val name = account?.displayName?.takeIf { it.isNotBlank() } ?: role.label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        // Members change their photo; for the others it is one of the membership's features.
        val avatar = Modifier.clickable(onClick = onPickAvatar ?: onPhotoOffers)
        Box(avatar, contentAlignment = Alignment.Center) {
            AsyncAvatar(url = account?.avatarUrl, initial = name, size = 64.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
            EonaText(name, style = EonaTheme.typography.titleLarge, color = colors.textPrimary)
            EonaBadge(role.label, glow = true)
            EonaText(
                if (onPickAvatar != null) "Changer la photo" else "Photo réservée aux membres",
                style = EonaTheme.typography.caption,
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onPickAvatar ?: onPhotoOffers),
            )
        }
    }
}

/** Status of the account: trial with its end, membership with its end, or restricted. */
@Composable
private fun AccessCard(account: Account?) {
    val colors = EonaTheme.colors
    EonaCard {
        Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
            EonaText("Statut", style = EonaTheme.typography.caption, color = colors.textTertiary)
            EonaText(
                com.eona.app.feature.menu.accessLabel(account),
                style = EonaTheme.typography.headline,
                color = colors.textPrimary,
            )
            if (account?.isRestricted == true) {
                EonaText(
                    "La carte reste disponible ; la navigation et les signalements reviennent avec un abonnement.",
                    style = EonaTheme.typography.subhead,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun VerifyEmailCard() {
    val colors = EonaTheme.colors
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    EonaCard {
        Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
            EonaText("Email non vérifié", style = EonaTheme.typography.headline, color = colors.textPrimary)
            EonaText("Entre le code reçu par email.", style = EonaTheme.typography.subhead, color = colors.textSecondary)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(colors.surface, EonaTheme.shapes.md)
                    .border(1.dp, colors.border, EonaTheme.shapes.md)
                    .padding(horizontal = EonaTheme.spacing.md, vertical = EonaTheme.spacing.md),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    singleLine = true,
                    textStyle = EonaTheme.typography.body.merge(androidx.compose.ui.text.TextStyle(color = colors.textPrimary)),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> if (code.isEmpty()) EonaText("Code à 6 chiffres", style = EonaTheme.typography.body, color = colors.textTertiary); inner() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
                Box(
                    Modifier.weight(1f).clip(EonaTheme.shapes.lg).background(colors.accent)
                        .clickable { scope.launch { msg = AccountRepository.verifyEmail(code) ?: "Email vérifié ✓" } }
                        .padding(vertical = EonaTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) { EonaText("Vérifier", style = EonaTheme.typography.bodyStrong, color = colors.onAccent) }
                Box(
                    Modifier.weight(1f).clip(EonaTheme.shapes.lg).border(1.dp, colors.border, EonaTheme.shapes.lg)
                        .clickable { scope.launch { AccountRepository.resendVerify(); msg = "Code renvoyé." } }
                        .padding(vertical = EonaTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) { EonaText("Renvoyer", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary) }
            }
            msg?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.accent) }
        }
    }
}

@Composable
private fun GuestNote(account: Account) {
    val reports = account.limits?.reportsPerDay ?: 5
    val trips = account.limits?.tripsPerDay ?: 7
    EonaCard {
        EonaText(
            "Compte invité : 7 jours d'essai gratuit, $reports signalements et $trips trajets par jour. " +
                "Ensuite, la carte seule sans abonnement.",
            style = EonaTheme.typography.subhead,
            color = EonaTheme.colors.textSecondary,
        )
    }
}

@Preview(name = "Profil · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun ProfileScreenPreview() {
    EonaTheme(darkTheme = true) {
        ProfileScreen(
            account = Account(id = "x", role = Role.Admin, username = "Arthur", displayName = "Arthur", avatarUrl = null, email = "a@b.com", banned = false),
            onBack = {},
        )
    }
}
