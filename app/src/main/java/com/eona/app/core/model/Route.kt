package com.eona.app.core.model

/** A computed driving route: the polyline to draw + distance, duration & steps. */
data class Route(
    val points: List<GeoPoint>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val steps: List<RouteStep> = emptyList(),
)
