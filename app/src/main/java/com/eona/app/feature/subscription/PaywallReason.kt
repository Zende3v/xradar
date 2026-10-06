package com.eona.app.feature.subscription

import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import com.eona.app.data.account.AccessDenial
import com.eona.app.data.account.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pourquoi les offres s'ouvrent (iOS PaywallReason) : compte bloqué, plafond du jour, ou fonction
 * EONA+. Règle d'or : une fonction réservée reste visible, son tap ouvre les offres.
 */
enum class PaywallReason {
    Restricted,
    ReportLimit,
    TripLimit,
    Taxi,
    Truck,
    Groups,
    Colours,
    Lights,
    Photo,
    Username;

    @Suppress("UNUSED_PARAMETER")
    fun title(account: Account?): String = when (this) {
        Restricted -> "EONA+"
        ReportLimit -> "Signalements du jour utilisés"
        TripLimit -> "Trajets du jour utilisés"
        Taxi -> "EONA Taxi"
        Truck -> "EONA Poids lourd"
        Groups -> "Trajet en groupe"
        Colours -> "Thème"
        Lights -> "Feux en direct"
        Photo -> "Photo de profil"
        Username -> "Pseudo"
    }

    @Suppress("UNUSED_PARAMETER")
    fun message(account: Account?): String = when (this) {
        Restricted -> "4 trajets par jour en gratuit. Trajets illimités avec EONA+."
        ReportLimit -> "Limite quotidienne atteinte. Reprise à minuit, heure de Paris."
        TripLimit -> "4 trajets utilisés. Reprise à minuit, heure de Paris."
        Taxi -> "Voies autorisées aux taxis, selon cartographie."
        Truck -> "Itinéraires adaptés au gabarit et aux restrictions de votre véhicule."
        Groups -> "Jusqu'à 5 conducteurs. Position et arrivée partagées."
        Colours -> "13 thèmes pour tracé, curseur et commandes."
        Lights -> "Temps restant avant le changement des feux sur votre trajet."
        Photo -> "Photo de profil, historique conservé. 7 jours EONA+ offerts."
        Username -> "Pseudo, email, mot de passe. 7 jours EONA+ offerts."
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
