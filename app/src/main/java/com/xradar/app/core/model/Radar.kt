package com.xradar.app.core.model

/**
 * A fixed radar from the official dataset. Pure model.
 * [code] is the source type (ETF/ETD/ETU speed radars, ETFR red-light…).
 */
data class Radar(
    val id: String,
    val code: String,
    val vma: Int?,
    val lat: Double,
    val lon: Double,
) {
    val isSpeedRadar: Boolean get() = !code.uppercase().startsWith("ETFR")

    val alertType: AlertType get() = if (isSpeedRadar) AlertType.RadarFixed else AlertType.Camera

    val displayTitle: String get() = if (isSpeedRadar) "Radar fixe" else "Radar feu rouge"
}
