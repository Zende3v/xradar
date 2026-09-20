package com.eona.app.core.model

/**
 * A probable radar-car zone, aggregated server-side from admin plate reports.
 * The circle tightens ([radiusMeters] shrinks) as more reports corroborate it.
 * The plate itself never leaves the server. Pure model.
 */
data class RadarZone(
    val id: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double,
    val count: Int,
)
