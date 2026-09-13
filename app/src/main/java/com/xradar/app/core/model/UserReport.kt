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
    /** Confirmations and contradictions the crowd has posted. */
    val confirmations: Int = 0,
    val contradictions: Int = 0,
    /** How many people reported it (the first one, plus every confirmation). */
    val reporters: Int = 1,
    /** "same" = the driver's own carriageway, "opposite" = the other one. */
    val direction: String = "same",
    /** Course of the reporter, in degrees — orients a control zone on the map. */
    val bearingDeg: Double? = null,
    /** Relevance right now (0..100), decayed by the backend since the last report. */
    val score: Int = 0,
    /** How far ahead this type is worth warning about, in metres. */
    val impactMeters: Double = 1500.0,
    /** A fixed camera: it never decays and only an admin removes it. */
    val persistent: Boolean = false,
    /** "guest" / "client" / "admin" — informational; guests and members count alike. */
    val reporterRole: String = "guest",
    /** Camera reports: the precise street and side ("left"/"right"). */
    val street: String? = null,
    val side: String? = null,
) {
    /** "Mon sens" / "Sens opposé". */
    val directionLabel: String
        get() = if (direction == "opposite") "Sens opposé" else "Mon sens"

    /** "3 signalements · il y a 12 min" — who saw it, and how fresh that is. */
    val crowdLabel: String
        get() = (if (reporters > 1) "$reporters signalements" else "1 signalement") + " · " + ageLabel

    /** "à gauche" / "à droite" / null. */
    val sideLabel: String?
        get() = when (side) {
            "left" -> "à gauche"
            "right" -> "à droite"
            else -> null
        }

    /** The intrinsic score as a 0..1 gauge, for the reliability bar. */
    val confidence: Float
        get() = (score / 100f).coerceIn(0f, 1f)

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
