package com.eona.app.feature.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import com.eona.app.data.account.AccountRepository
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.menu.accessLabel
import com.eona.app.feature.menu.shortDate

/**
 * "Abonnement": where the account stands; a running subscription's details, or else today's
 * limits and the plans. No payment yet.
 */
@Composable
fun SubscriptionRoute(onBack: () -> Unit) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    // Days and counts move on their own: read them fresh.
    LaunchedEffect(Unit) { AccountRepository.reload() }
    SubscriptionScreen(account = account, onBack = onBack)
}

@Composable
fun SubscriptionScreen(account: Account?, onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    EonaScreenScaffold(title = "Abonnement", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            EonaCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaText("Statut", style = EonaTheme.typography.caption, color = colors.textTertiary)
                    EonaBadge(accessLabel(account), glow = true)
                    EonaText(status(account), style = EonaTheme.typography.subhead, color = colors.textSecondary)
                }
            }

            if (account?.isSubscriber == true) {
                EonaListGroup(title = "Ton abonnement") {
                    Info("Formule", if (account.role == Role.Admin) "Administrateur" else "Membre")
                    Divider()
                    Info("État", "Actif")
                    Divider()
                    Info("Échéance", account.accessEndsAt?.let { shortDate(it) } ?: "Sans échéance")
                }
                EonaListGroup(title = "Inclus") {
                    MembershipBenefits(Modifier.padding(spacing.lg))
                }
            } else {
                val limits = account?.limits
                if (limits != null && account.isRestricted.not()) {
                    EonaListGroup(title = "Aujourd'hui") {
                        Info("Signalements", "${limits.reportsUsed()} / ${limits.reportsPerDay}")
                        Divider()
                        Info("Trajets", "${limits.tripsUsed()} / ${limits.tripsPerDay}")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaText("OFFRES", style = EonaTheme.typography.caption, color = colors.textTertiary)
                    SubscriptionPlans()
                    EonaText("Le paiement dans l'app arrive bientôt.", style = EonaTheme.typography.footnote, color = colors.textTertiary)
                }
                EonaListGroup(title = "Avec l'abonnement") {
                    MembershipBenefits(Modifier.padding(spacing.lg))
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun Info(title: String, value: String) {
    EonaListRow(
        title = title,
        trailing = { EonaText(value, style = EonaTheme.typography.callout, color = EonaTheme.colors.textSecondary) },
    )
}

@Composable
private fun Divider() = EonaDivider(Modifier.padding(start = EonaTheme.spacing.lg))

private fun status(account: Account?): String {
    if (account == null) return "Connecte-toi pour voir ton statut."
    if (account.role == Role.Admin) return "Accès complet, sans limite."
    if (account.isRestricted) {
        val ended = if (account.role == Role.Client) "Ton abonnement est terminé" else "Ton essai gratuit est terminé"
        return "$ended : la carte reste disponible ; la navigation, les alertes et les signalements reviennent avec un abonnement."
    }
    if (account.role == Role.Client) return "Navigation, alertes et signalements sans limite."
    val perDay = account.limits?.let { "${it.reportsPerDay} signalements et ${it.tripsPerDay} trajets par jour" } ?: "avec des limites par jour"
    return "Essai gratuit jusqu'au ${shortDate(account.accessEndsAt)}, $perDay."
}
