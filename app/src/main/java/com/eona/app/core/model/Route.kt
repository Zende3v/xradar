package com.eona.app.core.model

/** A computed driving route: the polyline to draw + distance, duration & steps. */
data class Route(
    val points: List<GeoPoint>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val steps: List<RouteStep> = emptyList(),
    /** The engine that computed it ("ors", "osrm"…) and its map's date; null when the backend does not say. */
    val engine: String? = null,
    val mapVersion: String? = null,
)
