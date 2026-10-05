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
    /** Choix servi par le backend (Rapide, Éco) ; null : backend sans choix d'itinéraire. */
    val preference: RoutePreference? = null,
    /** Temps HERE avec trafic de la route entière (`travelS`, demandé au choix) ; null inconnu. */
    val trafficSeconds: Int? = null,
    /** Péage, autoroute, ferry traversés (Valhalla, `roads`) ; null inconnu (ORS, ancien backend). */
    val roads: RouteRoads? = null,
)

/** Ce que traverse une route : péage, autoroute, ferry. */
data class RouteRoads(val toll: Boolean, val motorway: Boolean, val ferry: Boolean)
