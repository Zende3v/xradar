package com.xradar.app.core.model

/**
 * A crowdsourced report as seen by the app. Pure model — the age label and
 * confidence are derived in the data layer from the backend's counters.
 */
data class UserReport(
    val id: String,
    val type: ReportType,
    val lat: Double,
    val lon: Double,
    /** Milliseconds since the report was created (from the backend). */
    val ageMillis: Long,
    val confirms: Int,
    val denials: Int,
    /** Posted by an admin → fully trusted. */
    val trusted: Boolean = false,
    /** Camera reports: the precise street and side ("left"/"right"). */
    val street: String? = null,
    val side: String? = null,
) {
    /** "à gauche" / "à droite" / null. */
    val sideLabel: String?
        get() = when (side) {
            "left" -> "à gauche"
            "right" -> "à droite"
            else -> null
        }

    /** 0.4f..1f — more confirmations & freshness → higher confidence; admins = 1f. */
    val confidence: Float
        get() {
            if (trusted) return 1f
            val net = (confirms - denials).coerceAtLeast(0)
            return (0.5f + net * 0.15f).coerceIn(0.4f, 1f)
        }

    /** "à l'instant", "il y a 5 min", "il y a 2 h". */
    val ageLabel: String
        get() {
            val minutes = (ageMillis / 60_000L).toInt()
            return when {
                minutes < 1 -> "à l'instant"
                minutes < 60 -> "il y a $minutes min"
                else -> "il y a ${minutes / 60} h"
            }
        }
}
