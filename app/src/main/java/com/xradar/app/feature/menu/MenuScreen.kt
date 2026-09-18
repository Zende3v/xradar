package com.xradar.app.feature.menu

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.R
import com.xradar.app.core.model.Access
import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.profile.AsyncAvatar

/**
 * "Menu" — the first page: who you are (avatar, email, trust stars), then the sections, the
 * admin-only referral page, the legal notices, and sign-out at the bottom. Its icons and the
 * access badge glow white on dark tiles.
 */
@Composable
fun MenuRoute(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReferral: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    // The access status moves on its own (trial ending, referral applied): refresh.
    LaunchedEffect(Unit) { AccountRepository.reload() }
    MenuScreen(
        account = account,
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onOpenSubscription = onOpenSubscription,
        onOpenStats = onOpenStats,
        onOpenSettings = onOpenSettings,
        onOpenReferral = onOpenReferral,
        onOpenLegal = onOpenLegal,
        onOpenPrivacy = onOpenPrivacy,
        onLogout = { AccountRepository.logout() },
    )
}

@Composable
fun MenuScreen(
    account: Account?,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReferral: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    XRadarScreenScaffold(title = "Menu", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Identity(account)

            XRadarListGroup {
                Section("Mon compte", ImageVector.vectorResource(R.drawable.ic_account), onOpenAccount)
                XRadarDivider(Modifier.padding(start = 58.dp))
                Section("Abonnement", XRadarIcons.Star, onOpenSubscription)
                XRadarDivider(Modifier.padding(start = 58.dp))
                Section("Statistiques", ImageVector.vectorResource(R.drawable.ic_stats), onOpenStats)
                XRadarDivider(Modifier.padding(start = 58.dp))
                Section("Réglages", ImageVector.vectorResource(R.drawable.ic_settings), onOpenSettings)
                XRadarDivider(Modifier.padding(start = 58.dp))
                Section("Confidentialité", XRadarIcons.Shield, onOpenPrivacy)
                if (account?.role == Role.Admin) {
                    XRadarDivider(Modifier.padding(start = 58.dp))
                    Section("Parrainage", ImageVector.vectorResource(R.drawable.ic_referral), onOpenReferral)
                }
            }

            XRadarListGroup {
                Section("À propos", XRadarIcons.Info, onOpenLegal)
            }

            Spacer(Modifier.height(spacing.xl))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(XRadarTheme.shapes.lg)
                    .border(BorderStroke(1.dp, colors.danger), XRadarTheme.shapes.lg)
                    .clickable(onClick = onLogout)
                    .padding(vertical = spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                XRadarText("Se déconnecter", style = XRadarTheme.typography.bodyStrong, color = colors.danger)
            }
        }
    }
}

/** Avatar (or the role in a circle), email, trust stars and access status. */
@Composable
private fun Identity(account: Account?) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val role = account?.role ?: Role.Guest
    val name = account?.displayName?.takeIf { it.isNotBlank() } ?: role.label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        AsyncAvatar(url = account?.avatarUrl, initial = name, size = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            XRadarText(
                account?.email ?: name,
                style = XRadarTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
            )
            TrustStars(account?.trust ?: 2.5)
            XRadarBadge(accessLabel(account), glow = true)
        }
    }
}

/** Five stars, filled to the trust score (0..5, half-star precision). */
@Composable
fun TrustStars(score: Double) {
    val colors = XRadarTheme.colors
    val rounded = (score * 2).let { kotlin.math.round(it) / 2.0 }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { i ->
            val fill = (rounded - i).coerceIn(0.0, 1.0)
            XRadarIcon(
                XRadarIcons.Star,
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
        XRadarText(
            "%.1f".format(rounded).replace('.', ','),
            style = XRadarTheme.typography.caption,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, onClick: () -> Unit) {
    XRadarListRow(
        title = title,
        leadingIcon = icon,
        glow = true,
        onClick = onClick,
        trailing = {
            XRadarIcon(XRadarIcons.ChevronRight, contentDescription = null, tint = XRadarTheme.colors.textTertiary, size = 20.dp)
        },
    )
}

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
