package com.xradar.app.feature.drive.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.xradar.app.core.model.AlertType
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Bridge between the pure [AlertType] domain enum and the design system: this is
 * the only place event types map to a color token and an icon.
 */
@Composable
@ReadOnlyComposable
fun AlertType.color(): Color = when (this) {
    AlertType.RadarFixed -> XRadarTheme.colors.radarFixed
    AlertType.RadarMobile -> XRadarTheme.colors.radarMobile
    AlertType.ControlZone -> XRadarTheme.colors.controlZone
    AlertType.Camera -> XRadarTheme.colors.radarFixed
    AlertType.Hazard -> XRadarTheme.colors.hazard
    AlertType.Accident -> XRadarTheme.colors.hazard
    AlertType.Roadwork -> XRadarTheme.colors.controlZone
    AlertType.RadarCar -> XRadarTheme.colors.radarMobile
}

fun AlertType.icon(): ImageVector = when (this) {
    AlertType.RadarFixed -> XRadarIcons.Radar
    AlertType.RadarMobile -> XRadarIcons.Radar
    AlertType.ControlZone -> XRadarIcons.Shield
    AlertType.Camera -> XRadarIcons.Camera
    AlertType.Hazard -> XRadarIcons.Warning
    AlertType.Accident -> XRadarIcons.Accident
    AlertType.Roadwork -> XRadarIcons.Construction
    AlertType.RadarCar -> XRadarIcons.RadarCar
}
