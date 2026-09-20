package com.eona.app.data.radar

import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.Radar

/**
 * Radar data access. A failed request comes back as null — distinct from "no radars
 * here" — so the HUD keeps what it has and retries instead of wiping the map.
 */
class RadarRepository(private val api: RadarApi = RadarApi()) {
    suspend fun near(lat: Double, lon: Double, radiusM: Int): List<Radar>? =
        runCatching { api.near(lat, lon, radiusM) }.getOrNull()

    /** Radars along the trip's route; null when the backend could not answer. */
    suspend fun route(points: List<GeoPoint>): List<Radar>? =
        runCatching { api.route(points) }.getOrNull()
}
