package com.eona.app.feature.drive.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.eona.app.core.model.AlertType
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Bridge between the pure [AlertType] domain enum and the design system: this is
 * the only place event types map to a color token and an icon.
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

fun AlertType.icon(): ImageVector = when (this) {
    AlertType.RadarFixed -> EonaIcons.Radar
    AlertType.RadarMobile -> EonaIcons.Radar
    AlertType.ControlZone -> EonaIcons.Shield
    AlertType.Camera -> EonaIcons.Camera
    AlertType.Hazard -> EonaIcons.Warning
    AlertType.Accident -> EonaIcons.Accident
    AlertType.Roadwork -> EonaIcons.Construction
    AlertType.RadarCar -> EonaIcons.RadarCar
}
