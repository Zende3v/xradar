package com.eona.app.feature.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.core.model.Account
import com.eona.app.core.model.SubscriptionPlan
import com.eona.app.data.account.AccountRepository
import com.eona.app.designsystem.component.EonaConfirmDialog
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.menu.accessLabel
import com.eona.app.feature.onboarding.ConvertingOverlay

@Composable
fun SubscriptionRoute(onBack: () -> Unit, onOpenAccount: () -> Unit) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val hasPlus by AccountRepository.hasPlus.collectAsStateWithLifecycle()
    // Days and counts move on their own: read them fresh.
    LaunchedEffect(Unit) { AccountRepository.reload() }
    SubscriptionScreen(account = account, hasPlus = hasPlus, onBack = onBack, onOpenAccount = onOpenAccount)
}

/** Présentation EONA+ (iOS SubscriptionScreen) : radar animé, offres compactes, fonctions illustrées. */
@Composable
fun SubscriptionScreen(account: Account?, hasPlus: Boolean, onBack: () -> Unit, onOpenAccount: () -> Unit) {
    var plan by remember { mutableStateOf(SubscriptionPlan.YEARLY) }
    var benefit by remember { mutableStateOf<MembershipBenefit?>(null) }
    var registering by remember { mutableStateOf(false) }
    var paymentUnavailable by remember { mutableStateOf(false) }
    val canTry = account?.isGuest == true && !account.isRestricted
    LaunchedEffect(account?.isGuest) { if (account?.isGuest == false) registering = false }

    EonaPlusPage(
        onBack = onBack,
        onClose = null,
        footer = if (account != null && !account.isRestricted) {
            {
                when {
                    canTry -> EonaPlusAction("Essayer 7 jours gratuits", { registering = true })
                    hasPlus -> EonaPlusAction("Mon compte EONA+", onOpenAccount, icon = EonaIcons.Check)
                    else -> PayAction(plan) { paymentUnavailable = true }
                }
            }
        } else {
            null
        },
    ) {
        EonaPlusContent(
            title = "EONA+",
            subtitle = "Allez au-delà des limites et débloquez davantage d'exclusivités.",
            compact = false,
            animationsEnabled = !registering && !paymentUnavailable && benefit == null,
            accessLine = account?.let { accessLabel(it) },
            plan = plan,
            onPlan = { plan = it },
            onBenefit = { benefit = it },
        )
    }

    MembershipBenefitSheet(benefit, onClose = { benefit = null })
    if (paymentUnavailable) PaymentUnavailable { paymentUnavailable = false }
    if (registering) ConvertingOverlay(onClose = { registering = false })
}

/**
 * Page EONA+ : fond OLED, barre transparente, contenu défilant, action flottante sans bandeau.
 * [onBack] : chevron à gauche ; [onClose] : croix à droite.
 */
@Composable
fun EonaPlusPage(
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    footer: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = EonaTheme.spacing
    var footerHeight by remember { mutableIntStateOf(0) }
    val footerDp = with(LocalDensity.current) { footerHeight.toDp() }
    Box(
        Modifier
            .fillMaxSize()
            .background(EonaPlusStyle.canvas),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = spacing.sm),
            ) {
                if (onBack != null) {
                    TopIcon(EonaIcons.ChevronLeft, "Retour", Modifier.align(Alignment.CenterStart), onBack)
                }
                EonaText("EONA+", style = EonaTheme.typography.headline, color = EonaPlusStyle.primary, modifier = Modifier.align(Alignment.Center))
                if (onClose != null) {
                    TopIcon(EonaIcons.Close, "Fermer", Modifier.align(Alignment.CenterEnd), onClose)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg)
                    .padding(bottom = footerDp + spacing.xxl),
            ) {
                Column(Modifier.widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(spacing.xxxl), content = content)
            }
        }
        if (footer != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .onSizeChanged { footerHeight = it.height }
                    .padding(horizontal = spacing.lg)
                    .padding(top = spacing.md, bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                content = footer,
            )
        }
    }
}

@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        EonaIcon(icon, contentDescription = null, tint = EonaPlusStyle.mineral, size = 22.dp)
    }
}

/** Contenu commun de la page et de la fenêtre d'offre. */
@Composable
fun EonaPlusContent(
    title: String,
    subtitle: String,
    compact: Boolean,
    animationsEnabled: Boolean,
    accessLine: String?,
    plan: SubscriptionPlan,
    onPlan: (SubscriptionPlan) -> Unit,
    onBenefit: (MembershipBenefit) -> Unit,
) {
    val spacing = EonaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxxl)) {
        EonaPlusHero(subtitle = subtitle, title = title, compact = compact, animationsEnabled = animationsEnabled)
        accessLine?.let { EonaText(it, style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary) }
        SubscriptionPlans(plan, onPlan)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            EonaPlusLabel("Vos avantages EONA+", Modifier.padding(start = spacing.lg))
            MembershipSummary(onBenefit)
        }
        MembershipComparison()
        EonaText(
            "Apple Music / autres reste inclus en gratuit.",
            style = EonaTheme.typography.footnote,
            color = EonaPlusStyle.secondary,
            modifier = Modifier.padding(horizontal = spacing.lg),
        )
        Spacer(Modifier.height(0.dp))
    }
}

/** Formule choisie, sans paiement possible : la vente reste fermée. */
@Composable
fun ColumnScope.PayAction(plan: SubscriptionPlan, onClick: () -> Unit) {
    EonaText("Paiement indisponible actuellement.", style = EonaTheme.typography.caption, color = EonaPlusStyle.secondary)
    EonaPlusAction("S'abonner pour ${plan.priceLabel} ${plan.periodLabel}", onClick)
}

@Composable
fun PaymentUnavailable(onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EonaConfirmDialog(
            title = "Abonnement EONA+",
            message = "Paiement indisponible actuellement. Aucun achat effectué.",
            confirmLabel = "Fermer",
            onCancel = onClose,
            onConfirm = onClose,
            destructive = false,
            cancelLabel = null,
        )
    }
}
