package com.eona.app.feature.subscription

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.core.model.Account
import com.eona.app.core.model.SubscriptionPlan
import com.eona.app.data.account.AccountRepository
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.feature.menu.accessLabel
import com.eona.app.feature.onboarding.ConvertingOverlay

/**
 * Offre contextuelle (iOS OffersSheet) : même rythme et mêmes pastels que la page EONA+, avec la
 * raison en titre. Invité : essai par création de compte ; gratuit : formule, paiement fermé.
 * À placer en dernier dans une boîte plein écran.
 */
@Composable
fun OffersSheet(reason: PaywallReason, account: Account?, onClose: () -> Unit) {
    val live by AccountRepository.account.collectAsStateWithLifecycle()
    val current = live ?: account
    var plan by remember { mutableStateOf(SubscriptionPlan.YEARLY) }
    var benefit by remember { mutableStateOf<MembershipBenefit?>(null) }
    var registering by remember { mutableStateOf(false) }
    var paymentUnavailable by remember { mutableStateOf(false) }
    val canTry = current?.isGuest == true && !current.isRestricted
    // Invité devenu membre : essai ouvert, la fenêtre se ferme.
    var wasGuest by remember { mutableStateOf(current?.isGuest == true) }
    LaunchedEffect(current?.isGuest) {
        if (wasGuest && current?.isGuest == false) {
            registering = false
            onClose()
        }
        wasGuest = current?.isGuest == true
    }
    BackHandler(enabled = !registering && benefit == null, onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            // Les touches restent dans la fenêtre.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        EonaPlusPage(
            onBack = null,
            onClose = onClose,
            footer = {
                when {
                    canTry -> EonaPlusAction("Essayer 7 jours gratuits", { registering = true })
                    current != null && !current.isGuest && !current.isRestricted && !current.hasPlus() ->
                        PayAction(plan) { paymentUnavailable = true }
                    else -> EonaPlusAction("Fermer", onClose, icon = EonaIcons.Close)
                }
            },
        ) {
            EonaPlusContent(
                title = reason.title(current),
                subtitle = reason.message(current),
                compact = true,
                animationsEnabled = !registering && !paymentUnavailable && benefit == null,
                accessLine = accessLabel(current),
                plan = plan,
                onPlan = { plan = it },
                onBenefit = { benefit = it },
            )
        }
        MembershipBenefitSheet(benefit, onClose = { benefit = null })
        if (paymentUnavailable) PaymentUnavailable { paymentUnavailable = false }
        if (registering) ConvertingOverlay(onClose = { registering = false })
    }
}
