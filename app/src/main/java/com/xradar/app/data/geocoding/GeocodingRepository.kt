package com.xradar.app.data.geocoding

import com.xradar.app.core.model.Place

class GeocodingRepository(private val api: GeocodingApi = GeocodingApi()) {
    suspend fun search(query: String): List<Place> =
        runCatching { api.search(query) }.getOrDefault(emptyList())
}
