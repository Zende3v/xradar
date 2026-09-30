package com.eona.app.feature.drive.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.eona.app.R
import com.eona.app.core.model.AlertType
import com.eona.app.core.model.ReportType
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Bridge between the pure [AlertType] and [ReportType] domain enums and the design system: this
 * is the only place event types map to a color token and an icon. The icons are Arthur's report
 * set (30/09, res/drawable/ic_report_*), one per kind, tinted wherever they are drawn: the map's
 * markers, the report picker, the alerts, the toggles.
 */
@Composable
@ReadOnlyComposable
fun AlertType.color(): Color = when (this) {
    AlertType.RadarFixed -> EonaTheme.colors.radarFixed
    AlertType.RadarMobile -> EonaTheme.colors.radarMobile
    AlertType.ControlZone -> EonaTheme.colors.controlZone
    AlertType.Camera -> EonaTheme.colors.radarFixed
    AlertType.Hazard -> EonaTheme.colors.hazard
    AlertType.Accident -> EonaTheme.colors.hazard
    AlertType.Roadwork -> EonaTheme.colors.controlZone
    AlertType.RadarCar -> EonaTheme.colors.radarMobile
}

@Composable
fun AlertType.icon(): ImageVector = iconRes()?.let { ImageVector.vectorResource(it) } ?: EonaIcons.Warning

/** The drawable of each alert kind; a plain danger has none in the set (the warning sign then). */
fun AlertType.iconRes(): Int? = when (this) {
    AlertType.RadarFixed -> R.drawable.ic_report_radar_fixed
    AlertType.RadarMobile -> R.drawable.ic_report_radar_mobile
    AlertType.ControlZone -> R.drawable.ic_report_control_zone
    AlertType.Camera -> R.drawable.ic_report_camera
    AlertType.Hazard -> null
    AlertType.Accident -> R.drawable.ic_report_accident
    AlertType.Roadwork -> R.drawable.ic_report_roadworks
    AlertType.RadarCar -> R.drawable.ic_report_radar_car
}

/** A report's own icon: finer than its alert's (a stopped vehicle, an object, a jam…). */
@Composable
fun ReportType.icon(): ImageVector = iconRes()?.let { ImageVector.vectorResource(it) } ?: EonaIcons.Warning

fun ReportType.iconRes(): Int? = when (this) {
    ReportType.VoitureRadar -> R.drawable.ic_report_radar_car
    ReportType.Camera -> R.drawable.ic_report_camera
    ReportType.Hazard -> null
    ReportType.RadarMobile -> R.drawable.ic_report_radar_mobile
    ReportType.ControlZone -> R.drawable.ic_report_control_zone
    ReportType.StoppedVehicle -> R.drawable.ic_report_stopped_vehicle
    ReportType.Accident -> R.drawable.ic_report_accident
    ReportType.ObjectOnRoad -> R.drawable.ic_report_object_on_road
    ReportType.TrafficJam -> R.drawable.ic_report_traffic_jam
    ReportType.DamagedRoad -> R.drawable.ic_report_damaged_road
    ReportType.Roadworks -> R.drawable.ic_report_roadworks
    ReportType.SlipperyRoad -> R.drawable.ic_report_slippery_road
    ReportType.LowVisibility -> R.drawable.ic_report_low_visibility
    ReportType.RoadCrew -> R.drawable.ic_report_road_crew
    ReportType.WrongWay -> R.drawable.ic_report_wrong_way
}
