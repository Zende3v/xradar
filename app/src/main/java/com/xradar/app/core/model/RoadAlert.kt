package com.xradar.app.core.model

/** Kind of road event. Colors and icons are assigned in the UI layer, not here. */
enum class AlertType { RadarFixed, RadarMobile, ControlZone, Camera, Hazard, Accident, Roadwork, RadarCar }

/**
 * A single road event surfaced to the driver. Pure model — no Android/Compose
 * types — so the alert engine (Phase 5) and a future iOS port can reuse it as-is.
 */
data class RoadAlert(
    val type: AlertType,
    val title: String,
    val roadLabel: String?,
    val speedLimitKmh: Int?,
    val distanceMeters: Int,
    val etaSeconds: Int,
    /** 0f..1f confidence in this event. */
    val confidence: Float,
    /** For crowdsourced events (e.g. control zones): when it was last reported. */
    val lastReportedLabel: String?,
    /** Stable id of the source radar/report, for voice-announcement de-duplication. */
    val id: String? = null,
)
