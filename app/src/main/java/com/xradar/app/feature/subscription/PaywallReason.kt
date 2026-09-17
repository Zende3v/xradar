package com.xradar.app.feature.subscription

import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
import com.xradar.app.data.account.AccessDenial
import com.xradar.app.data.account.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why the offers show: the account is blocked, a guest reached a limit of the day, or the
 * action is a members' feature.
 */
enum class PaywallReason {
    Restricted,
    ReportLimit,
    TripLimit,
    Music,
    Photo;

    fun title(account: Account?): String = when (this) {
        Restricted -> if (account?.role == Role.Client) "Ton abonnement est terminé" else "Ton essai gratuit est terminé"
        ReportLimit -> "Signalements du jour utilisés"
        TripLimit -> "Trajets du jour utilisés"
        Music -> "Musique réservée aux membres"
        Photo -> "Photo de profil réservée aux membres"
    }

    fun message(account: Account?): String = when (this) {
        Restricted -> "La carte reste disponible. Abonne-toi pour retrouver la navigation, les alertes et les signalements."
        ReportLimit -> "Un compte invité peut signaler ${account?.limits?.reportsPerDay ?: 5} fois par jour. Les membres signalent sans limite."
        TripLimit -> "Un compte invité peut lancer ${account?.limits?.tripsPerDay ?: 7} trajets par jour. Les membres naviguent sans limite."
        Music -> "Le raccourci musique pendant la conduite fait partie de l'abonnement."
        Photo -> "Ajoute ta photo de profil avec l'abonnement membre."
    }

    companion object {
        fun of(denial: AccessDenial): PaywallReason = when (denial) {
            AccessDenial.SubscriptionRequired -> Restricted
            AccessDenial.DailyReportLimit -> ReportLimit
            AccessDenial.DailyTripLimit -> TripLimit
        }
    }
}

/** The offers waiting to show over the map, and why. */
object OffersPrompt {
    private val _reason = MutableStateFlow<PaywallReason?>(null)
    val reason: StateFlow<PaywallReason?> = _reason.asStateFlow()

    fun show(reason: PaywallReason) {
        _reason.value = reason
    }

    fun dismiss() {
        _reason.value = null
    }

    /** The app came to the front, or the account just got blocked: a blocked account sees the offers. */
    fun offerIfRestricted() {
        if (AccountRepository.account.value?.isRestricted == true && _reason.value == null) {
            _reason.value = PaywallReason.Restricted
        }
    }
}
