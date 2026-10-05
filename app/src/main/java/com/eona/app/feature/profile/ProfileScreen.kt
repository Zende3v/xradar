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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.BuildConfig
import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import com.eona.app.core.model.TripRecord
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.AccountStats
import com.eona.app.data.stats.TripHistoryRepository
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaConfirmDialog
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.menu.StatsSections
import com.eona.app.feature.menu.TripDetailScreen
import com.eona.app.feature.menu.accessLabel
import com.eona.app.feature.menu.displayName
import com.eona.app.feature.menu.loadStats
import com.eona.app.feature.menu.shortDate
import com.eona.app.feature.subscription.OffersSheet
import com.eona.app.feature.subscription.PaywallReason
import kotlinx.coroutines.launch

@Composable
fun ProfileRoute(onBack: () -> Unit) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var stats by remember { mutableStateOf<AccountStats?>(null) }
    var statsLoaded by remember { mutableStateOf(false) }
    var trip by remember { mutableStateOf<TripRecord?>(null) }
    LaunchedEffect(Unit) {
        stats = loadStats(context)
        statsLoaded = true
    }
    trip?.let {
        TripDetailScreen(it, onBack = { trip = null })
        return
    }
    ProfileScreen(
        account = account,
        stats = stats,
        statsLoaded = statsLoaded,
        onOpenTrip = { trip = it },
        onBack = onBack,
    )
}

/**
 * « Mon compte & Statistiques » (iOS ProfileScreen), de haut en bas : profil (photo, nom
 * modifiable, statut), comptes liés (Google ; Apple bientôt), statistiques, puis version et
 * suppression du compte. Véhicule : dans Réglages.
 */
@Composable
fun ProfileScreen(
    account: Account?,
    stats: AccountStats?,
    statsLoaded: Boolean,
    onOpenTrip: (TripRecord) -> Unit,
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
    EonaScreenScaffold(title = "Mon compte & Statistiques", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Header(
                    account = account,
                    onPhoto = { if (account?.canEditProfile == true) picker.launch("image/*") else offers = PaywallReason.Photo },
                    onRename = { if (account?.canChangeUsername == true) renaming = true else offers = PaywallReason.Username },
                )
                when {
                    account?.isRestricted == true -> Note("Navigation et signalements : avec EONA +.")
                    account?.role == Role.Guest ->
                        Note("Essai 7 jours · ${account.limits?.reportsPerDay ?: 5} signalements et ${account.limits?.tripsPerDay ?: 7} trajets par jour.")
                }
            }

            if (account?.email != null && account.emailVerified == false) {
                VerifyEmailCard()
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaListGroup(title = "Comptes liés") {
                    if (com.eona.app.data.account.GoogleAuth.isAvailable && account != null) {
                        val linked = "google" in account.providers
                        LinkedAccountRow(
                            mark = null,
                            title = "Google",
                            subtitle = if (linked) "Lié" else "Non lié",
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
                        ) {
                            if (linking) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                            } else {
                                EonaText(
                                    if (linked) "Dissocier" else "Lier",
                                    style = EonaTheme.typography.label,
                                    color = if (linked) colors.textSecondary else colors.accent,
                                )
                            }
                        }
                        EonaDivider(Modifier.padding(start = 62.dp))
                    }
                    LinkedAccountRow(mark = EonaIcons.AppleMark, title = "Apple", subtitle = null, onClick = null) {
                        EonaBadge("Bientôt", color = colors.textTertiary)
                    }
                }
                linkMessage?.let {
                    EonaText(it, style = EonaTheme.typography.footnote, color = colors.textSecondary, modifier = Modifier.padding(horizontal = spacing.md))
                }
            }

            StatsSections(stats = stats, loaded = statsLoaded, onOpenTrip = onOpenTrip)

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
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
                EonaText(
                    "EONA ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                )
            }

            Spacer(Modifier.height(spacing.xl))
        }

        offers?.let { reason -> OffersSheet(reason, account, onClose = { offers = null }) }

        if (renaming) {
            UsernameDialog(current = account?.username.orEmpty(), onClose = { renaming = false })
        }

        if (confirmDelete) {
            EonaConfirmDialog(
                title = "Supprimer ton compte ?",
                message = "Compte, photo, statistiques et trajets effacés pour de bon. Tes signalements restent, sans ton nom.",
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

/**
 * Photo (touchée : changée, ou offre EONA +), nom (crayon : pseudo, une fois par semaine ; sinon
 * l'offre), statut.
 */
@Composable
private fun Header(account: Account?, onPhoto: () -> Unit, onRename: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val role = account?.role ?: Role.Guest
    val name = displayName(account)
    val editable = account?.canEditProfile == true
    val canRename = account?.canChangeUsername == true
    val access = accessLabel(account)
    val wait = account?.usernameChangeableAt
        ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        ?.takeIf { it.isAfter(java.time.Instant.now()) }
        ?.let { "Prochain changement le ${shortDate(account.usernameChangeableAt)}" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onPhoto)
                .semantics { contentDescription = if (editable) "Changer la photo" else "Photo réservée aux membres" },
        ) {
            AsyncAvatar(url = account?.avatarUrl, initial = name, size = 72.dp)
            if (editable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .border(2.dp, colors.canvas, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    EonaIcon(EonaIcons.Camera, contentDescription = null, tint = colors.onAccent, size = 13.dp)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            // Nom touché : pseudo changé. Client actif seulement, une fois par semaine (backend) ;
            // sinon l'offre EONA +.
            Row(
                modifier = Modifier
                    .clip(EonaTheme.shapes.sm)
                    .clickable(enabled = !(canRename && wait != null), onClick = onRename)
                    .semantics { contentDescription = "$name, changer de pseudo" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                EonaText(name, style = EonaTheme.typography.title, color = colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                EonaIcon(
                    EonaIcons.Pencil,
                    contentDescription = null,
                    tint = if (canRename && wait == null) colors.accent else colors.textTertiary,
                    size = 16.dp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaBadge(role.label, glow = true)
                if (access != role.label) {
                    EonaText(access, style = EonaTheme.typography.footnote, color = colors.textSecondary, maxLines = 1)
                }
            }
            wait?.let { EonaText(it, style = EonaTheme.typography.caption, color = colors.textTertiary) }
        }
    }
}

@Composable
private fun Note(text: String) {
    EonaText(
        text,
        style = EonaTheme.typography.footnote,
        color = EonaTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = EonaTheme.spacing.xs),
    )
}

/** Un compte lié : sa marque (pictogramme, ou initiale), son nom, son état, une action. */
@Composable
private fun LinkedAccountRow(
    mark: ImageVector?,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(EonaTheme.shapes.sm)
                .background(colors.textPrimary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (mark != null) {
                EonaIcon(mark, contentDescription = null, tint = colors.textPrimary, size = 18.dp)
            } else {
                EonaText(
                    title.take(1),
                    style = EonaTheme.typography.bodyStrong.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText(title, style = EonaTheme.typography.body, color = colors.textPrimary)
            subtitle?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.textTertiary) }
        }
        trailing()
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

@Preview(name = "Profil · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun ProfileScreenPreview() {
    EonaTheme(darkTheme = true) {
        ProfileScreen(
            account = Account(id = "x", role = Role.Admin, username = "Arthur", displayName = "Arthur", avatarUrl = null, email = "a@b.com", banned = false),
            stats = null,
            statsLoaded = true,
            onOpenTrip = {},
            onBack = {},
        )
    }
}
