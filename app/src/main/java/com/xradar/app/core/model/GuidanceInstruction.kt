package com.xradar.app.core.model

/** Visual class of a maneuver — selects the arrow shown on the guidance banner. */
enum class Maneuver {
    Depart,
    Straight,
    SlightLeft,
    SlightRight,
    Left,
    Right,
    SharpLeft,
    SharpRight,
    Uturn,
    Roundabout,
    Merge,
    Ramp,
    ForkLeft,
    ForkRight,
    Arrive,
}

/** The next maneuver to display (and announce) while navigating. */
data class GuidanceInstruction(
    val maneuver: Maneuver,
    val distanceMeters: Int,
    /** e.g. "Tournez à droite". */
    val primaryText: String,
    /** Road you turn onto, e.g. "Rue de la Paix" (null when unnamed). */
    val roadName: String?,
)
