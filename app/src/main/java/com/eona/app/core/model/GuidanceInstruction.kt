package com.eona.app.core.model

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
    /** Road you turn onto, e.g. "Rue de la Paix", or where a motorway branch leads, e.g.
     * "N 104 · Sénart, Corbeil-Essonnes" (null when unnamed). */
    val roadName: String?,
    /** The motorway exit's number, shown as its sign ("Sortie 8"). */
    val exitNumber: String? = null,
)
