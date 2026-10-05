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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.component.EonaGlowTile
import com.eona.app.designsystem.foundation.EonaIcons
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
 * « EONA + » (iOS SubscriptionScreen) : l'offre membre. Statut du compte ; EONA + actif : son
 * détail ; sinon les limites du jour et les formules. Pas encore de paiement.
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
    EonaScreenScaffold(title = "EONA +", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Hero(account)

            if (account?.isSubscriber == true) {
                EonaListGroup(title = "Ton EONA +") {
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
                EonaListGroup(title = "Inclus") {
                    MembershipBenefits(Modifier.padding(spacing.lg))
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaText("FORMULES", style = EonaTheme.typography.caption, color = colors.textTertiary, modifier = Modifier.padding(start = spacing.md))
                    SubscriptionPlans()
                    EonaText("Paiement dans l'app bientôt disponible.", style = EonaTheme.typography.footnote, color = colors.textTertiary, modifier = Modifier.padding(horizontal = spacing.md))
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** Couronne, nom, promesse, statut du compte. */
@Composable
private fun Hero(account: Account?) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        EonaGlowTile(EonaIcons.Crown, size = 72.dp, iconSize = 32.dp, shape = EonaTheme.shapes.xxl)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            EonaText("EONA +", style = EonaTheme.typography.titleLarge, color = colors.textPrimary)
            EonaText("Toute la route, sans limite.", style = EonaTheme.typography.callout, color = colors.textSecondary)
        }
        EonaBadge(accessLabel(account), glow = true)
        EonaText(status(account), style = EonaTheme.typography.footnote, color = colors.textTertiary, textAlign = TextAlign.Center)
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
    if (account.role == Role.Admin) return "Accès complet."
    if (account.isRestricted) {
        val ended = if (account.role == Role.Client) "EONA + terminé" else "Essai terminé"
        return "$ended. La carte reste disponible."
    }
    if (account.role == Role.Client) return "Tout est inclus, sans limite."
    val perDay = account.limits?.let { " · ${it.reportsPerDay} signalements et ${it.tripsPerDay} trajets par jour" } ?: ""
    return "Essai jusqu'au ${shortDate(account.accessEndsAt)}$perDay."
}
