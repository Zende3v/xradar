package com.xradar.app.data.routing

import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.Route

class RoutingRepository(private val api: RoutingApi = RoutingApi()) {
    suspend fun route(from: GeoPoint, to: GeoPoint, avoid: List<String> = emptyList()): Route? =
        runCatching { api.route(from, to, avoid) }.getOrNull()
}
