package com.eona.app.feature.subscription

import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import com.eona.app.data.account.AccessDenial
import com.eona.app.data.account.AccountRepository
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
    Photo,
    Username;

    fun title(account: Account?): String = when (this) {
        Restricted -> if (account?.role == Role.Client) "Ton EONA + est terminé" else "Ton essai gratuit est terminé"
        ReportLimit -> "Signalements du jour utilisés"
        TripLimit -> "Trajets du jour utilisés"
        Music -> "Musique avec EONA +"
        Photo -> "Photo avec EONA +"
        Username -> "Pseudo avec EONA +"
    }

    fun message(account: Account?): String = when (this) {
        Restricted -> "La carte reste disponible. EONA + rouvre navigation, alertes et signalements."
        ReportLimit -> "Invité : ${account?.limits?.reportsPerDay ?: 5} signalements par jour. Illimité avec EONA +."
        TripLimit -> "Invité : ${account?.limits?.tripsPerDay ?: 7} trajets par jour. Illimité avec EONA +."
        Music -> "Le raccourci musique au volant est inclus dans EONA +."
        Photo -> "Photo de profil incluse dans EONA +."
        Username -> "Changer de pseudo : inclus dans EONA +, une fois par semaine."
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
