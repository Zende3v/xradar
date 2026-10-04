package com.eona.app.core.model

/**
 * Permis probatoire (code de la route, art. R413-5) : 110 km/h au lieu de 130 sur autoroute,
 * 100 au lieu de 110 sur voie rapide, 80 au lieu de 90 hors agglomération. Autres limites
 * inchangées. Limitation officielle gardée à part : signalements, sondes, caméra. Mêmes règles
 * que l'app iOS (ProbationaryLimits.swift).
 */
object ProbationaryLimits {
    fun adjusted(kmh: Int): Int = when (kmh) {
        130 -> 110
        110 -> 100
        90 -> 80
        else -> kmh
    }

    fun adjusted(kmh: Int?, probationary: Boolean): Int? = if (probationary && kmh != null) adjusted(kmh) else kmh

    /**
     * Limite affichée : permis probatoire, puis plafond du véhicule ([capKmh], 45 pour scooter 50
     * et sans permis). Limite inconnue : inconnue.
     */
    fun shown(kmh: Int?, probationary: Boolean, capKmh: Int?): Int? {
        val limit = adjusted(kmh, probationary) ?: return null
        return capKmh?.let { minOf(limit, it) } ?: limit
    }
}
