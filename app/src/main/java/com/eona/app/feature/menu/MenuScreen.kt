package com.eona.app.feature.menu

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.R
import com.eona.app.core.model.Access
import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import com.eona.app.data.account.AccountRepository
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.profile.AsyncAvatar

/**
 * « Menu » (iOS MenuScreen) : identité, puis trois boîtes. 1 : Réglages, EONA +, Mon compte &
 * Statistiques, Confidentialité, À propos. 2 : « Contactez-nous ». 3, admins seulement :
 * Parrainage, Rapports. Déconnexion en bas.
 */
@Composable
fun MenuRoute(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReferral: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBugReport: () -> Unit,
    onOpenBugs: () -> Unit,
) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    // The access status moves on its own (trial ending, referral applied): refresh.
    LaunchedEffect(Unit) { AccountRepository.reload() }
    MenuScreen(
        account = account,
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onOpenSubscription = onOpenSubscription,
        onOpenSettings = onOpenSettings,
        onOpenReferral = onOpenReferral,
        onOpenLegal = onOpenLegal,
        onOpenPrivacy = onOpenPrivacy,
        onOpenBugReport = onOpenBugReport,
        onOpenBugs = onOpenBugs,
        onLogout = { AccountRepository.logout() },
    )
}

@Composable
fun MenuScreen(
    account: Account?,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReferral: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBugReport: () -> Unit,
    onOpenBugs: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    EonaScreenScaffold(title = "Menu", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Identity(account)

            EonaListGroup {
                Section("Réglages", ImageVector.vectorResource(R.drawable.ic_settings), onOpenSettings)
                EonaDivider(Modifier.padding(start = 58.dp))
                Section("EONA +", EonaIcons.Crown, onOpenSubscription)
                EonaDivider(Modifier.padding(start = 58.dp))
                Section("Mon compte & Statistiques", ImageVector.vectorResource(R.drawable.ic_account), onOpenAccount)
                EonaDivider(Modifier.padding(start = 58.dp))
                Section("Confidentialité", EonaIcons.Shield, onOpenPrivacy)
                EonaDivider(Modifier.padding(start = 58.dp))
                Section("À propos", EonaIcons.Info, onOpenLegal)
            }

            ContactCard(onOpenBugReport)

            if (account?.role == Role.Admin) {
                EonaListGroup(title = "Admin") {
                    Section("Parrainage", ImageVector.vectorResource(R.drawable.ic_referral), onOpenReferral)
                    EonaDivider(Modifier.padding(start = 58.dp))
                    Section("Rapports", EonaIcons.Warning, onOpenBugs)
                }
            }

            Spacer(Modifier.height(spacing.md))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EonaTheme.shapes.lg)
                    .border(BorderStroke(1.dp, colors.danger), EonaTheme.shapes.lg)
                    .clickable(onClick = onLogout)
                    .padding(vertical = spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                EonaText("Se déconnecter", style = EonaTheme.typography.bodyStrong, color = colors.danger)
            }
            Spacer(Modifier.height(spacing.lg))
        }
    }
}

/** « Un problème, une suggestion ? » — « Contactez-nous ! » : bugs et idées, un seul formulaire. */
@Composable
private fun ContactCard(onClick: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.xl)
            .background(colors.surfaceElevated)
            .border(1.5.dp, colors.accent.copy(alpha = 0.55f), EonaTheme.shapes.xl)
            .clickable(role = SemanticsRole.Button, onClick = onClick)
            .padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(EonaIcons.Chat, contentDescription = null, tint = colors.accent, size = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText("Un problème, une suggestion ?", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
            EonaText("Contactez-nous !", style = EonaTheme.typography.subhead, color = colors.accent)
        }
        EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, size = 18.dp)
    }
}

/** Avatar (or the role in a circle), email, trust stars and access status. */
@Composable
private fun Identity(account: Account?) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val name = displayName(account)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        AsyncAvatar(url = account?.avatarUrl, initial = name, size = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            EonaText(
                account?.email ?: name,
                style = EonaTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
            )
            TrustStars(account?.trust ?: 2.5)
            EonaBadge(accessLabel(account), glow = true)
        }
    }
}

/** Five stars, filled to the trust score (0..5, half-star precision). */
@Composable
fun TrustStars(score: Double) {
    val colors = EonaTheme.colors
    val rounded = (score * 2).let { kotlin.math.round(it) / 2.0 }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { i ->
            val fill = (rounded - i).coerceIn(0.0, 1.0)
            EonaIcon(
                EonaIcons.Star,
                contentDescription = null,
                tint = when {
                    fill >= 1.0 -> colors.warning
                    fill >= 0.5 -> colors.warning.copy(alpha = 0.55f)
                    else -> colors.borderStrong
                },
                size = 16.dp,
            )
        }
        Spacer(Modifier.padding(start = 4.dp))
        EonaText(
            "%.1f".format(rounded).replace('.', ','),
            style = EonaTheme.typography.caption,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, onClick: () -> Unit) {
    EonaListRow(
        title = title,
        leadingIcon = icon,
        glow = true,
        onClick = onClick,
        trailing = {
            EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = EonaTheme.colors.textTertiary, size = 20.dp)
        },
    )
}

/** Le nom affiché, sinon le rôle. */
fun displayName(account: Account?): String =
    account?.displayName?.takeIf { it.isNotBlank() } ?: (account?.role ?: Role.Guest).label

/** "Essai gratuit · 3 j restants", "Membre · jusqu'au 12/03/2027", "Accès restreint". */
fun accessLabel(account: Account?): String {
    if (account == null) return "Invité"
    return when {
        account.role == Role.Admin -> "Admin"
        account.access == Access.Restricted || !account.canNavigate -> "Accès restreint"
        account.access == Access.Trial -> "Essai gratuit · ${daysLeft(account.accessEndsAt)}"
        account.accessEndsAt != null -> "Membre · jusqu'au ${shortDate(account.accessEndsAt)}"
        else -> "Membre"
    }
}

private fun daysLeft(iso: String?): String {
    val end = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return "7 j"
    val days = ((end - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
    return if (days <= 1) "dernier jour" else "$days j restants"
}

fun shortDate(iso: String?): String {
    val instant = iso?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return "—"
    return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}
