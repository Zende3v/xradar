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
    VoitureRadar("voiture_radar", "Voiture radar", AlertType.RadarCar, Role.Client, needsPlate = true),
    Camera("camera", "Caméra", AlertType.Camera, Role.Admin),
    Hazard("hazard", "Danger", AlertType.Hazard, Role.Guest),

    RadarMobile("radar_mobile", "Radar mobile", AlertType.RadarMobile, Role.Guest),
    ControlZone("control_zone", "Zone de contrôle", AlertType.ControlZone, Role.Guest),
    StoppedVehicle("stopped_vehicle", "Véhicule arrêté", AlertType.Hazard, Role.Guest),
    Accident("accident", "Accident", AlertType.Accident, Role.Guest),
    ObjectOnRoad("object_on_road", "Objet sur la voie", AlertType.Hazard, Role.Guest),
    TrafficJam("traffic_jam", "Bouchon", AlertType.Hazard, Role.Guest),
    DamagedRoad("damaged_road", "Chaussée dégradée", AlertType.Hazard, Role.Guest),
    Roadworks("roadworks", "Travaux", AlertType.Roadwork, Role.Guest),
    SlipperyRoad("slippery_road", "Route glissante", AlertType.Hazard, Role.Guest),
    LowVisibility("low_visibility", "Visibilité réduite", AlertType.Hazard, Role.Guest),
    RoadCrew("road_crew", "Personnel autoroutier", AlertType.Roadwork, Role.Guest),
    WrongWay("wrong_way", "Véhicule à contresens", AlertType.Hazard, Role.Guest);

    /** Can an account with [role] create this report type? */
    fun allowedFor(role: Role): Boolean = role.ordinal >= minRole.ordinal

    /** Alerts for this category reach the driver (shown ahead, spoken, sounded). */
    val raisesAlerts: Boolean get() = this != TrafficJam

    companion object {
        fun fromWire(value: String?): ReportType? = entries.firstOrNull { it.wire == value }

        /**
         * The categories the driver turns off one by one in the HUD's "Options", in that order.
         * A traffic jam is no alert (the route can avoid it instead); the legacy hazard has no switch.
         */
        val ALERT_OPTIONS: List<ReportType> = listOf(
            RadarMobile,
            Camera,
            ControlZone,
            VoitureRadar,
            StoppedVehicle,
            Accident,
            ObjectOnRoad,
            DamagedRoad,
            Roadworks,
            SlipperyRoad,
            LowVisibility,
            RoadCrew,
            WrongWay,
        )

        /** What the report sheet offers, in the order it is shown (6 per page). */
        val PICKER: List<ReportType> = listOf(
            RadarMobile,
            ControlZone,
            VoitureRadar,
            StoppedVehicle,
            Accident,
            ObjectOnRoad,
            TrafficJam,
            DamagedRoad,
            Roadworks,
            SlipperyRoad,
            LowVisibility,
            RoadCrew,
            WrongWay,
            // Admin only, so it lands at the end of the last page.
            Camera,
        )
    }
}
