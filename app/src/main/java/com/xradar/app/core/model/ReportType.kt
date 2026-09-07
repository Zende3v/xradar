package com.xradar.app.core.model

/**
 * A crowdsourced report category. [wire] is exchanged with the backend; [alertType]
 * reuses the visual vocabulary; [minRole] is who may create it; [needsStreet] /
 * [needsPlate] flag the extra fields the report sheet must collect. Pure model.
 */
enum class ReportType(
    val wire: String,
    val label: String,
    val alertType: AlertType,
    val minRole: Role,
    val needsStreet: Boolean = false,
    val needsPlate: Boolean = false,
) {
    // Admin only: a radar car, reported by plate → aggregated into a probable zone.
    VoitureRadar("voiture_radar", "Voiture-radar", AlertType.RadarCar, Role.Admin, needsPlate = true),
    // Members + admins: a camera, with the precise street and side.
    Camera("camera", "Caméra", AlertType.Camera, Role.Client, needsStreet = true),
    // Everyone:
    RadarMobile("radar_mobile", "Radar mobile", AlertType.RadarMobile, Role.Guest),
    ControlZone("control_zone", "Zone de contrôle", AlertType.ControlZone, Role.Guest),
    Accident("accident", "Accident", AlertType.Accident, Role.Guest),
    Hazard("hazard", "Danger", AlertType.Hazard, Role.Guest);

    /** Can an account with [role] create this report type? */
    fun allowedFor(role: Role): Boolean = role.ordinal >= minRole.ordinal

    companion object {
        fun fromWire(value: String?): ReportType? = entries.firstOrNull { it.wire == value }
    }
}
