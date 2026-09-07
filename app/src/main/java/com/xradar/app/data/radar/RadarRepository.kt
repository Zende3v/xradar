package com.xradar.app.data.radar

import com.xradar.app.core.model.Radar

/**
 * Radar data access. Swallows failures to an empty list so a flaky network never
 * breaks the HUD. (Will gain caching + crowdsourced sources later.)
 */
class RadarRepository(private val api: RadarApi = RadarApi()) {
    suspend fun near(lat: Double, lon: Double, radiusM: Int): List<Radar> =
        runCatching { api.near(lat, lon, radiusM) }.getOrDefault(emptyList())
}
