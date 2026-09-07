package com.xradar.app.designsystem.foundation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke line-icon set (24dp grid, ~2px stroke). Every icon is drawn as a white
 * stroke; [com.xradar.app.designsystem.component.XRadarIcon] recolors it via tint,
 * so any semantic color applies. One coherent visual language across the whole app.
 */
object XRadarIcons {

    // ---- Directional & control ----
    val ArrowLeft = line("ArrowLeft") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
    val ChevronLeft = line("ChevronLeft") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
    val ChevronRight = line("ChevronRight") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val ChevronUp = line("ChevronUp") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val ChevronDown = line("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Close = line("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = line("Check") { moveTo(5f, 13f); lineTo(10f, 18f); lineTo(19.5f, 6.5f) }
    val Plus = line("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Minus = line("Minus") { moveTo(5f, 12f); lineTo(19f, 12f) }
    val Menu = line("Menu") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
    }
    val More = line("More", width = 2.6f) {
        moveTo(5f, 12f); lineTo(5f, 12.01f)
        moveTo(12f, 12f); lineTo(12f, 12.01f)
        moveTo(19f, 12f); lineTo(19f, 12.01f)
    }

    // ---- Sections & actions ----
    val Search = line("Search") {
        moveTo(4f, 11f); arcTo(7f, 7f, 0f, true, true, 18f, 11f); arcTo(7f, 7f, 0f, true, true, 4f, 11f)
        moveTo(16.2f, 16.2f); lineTo(21f, 21f)
    }
    val Settings = line("Settings") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(6.5f, 7f); arcTo(2.5f, 2.5f, 0f, true, true, 11.5f, 7f); arcTo(2.5f, 2.5f, 0f, true, true, 6.5f, 7f)
        moveTo(13f, 12f); arcTo(2.5f, 2.5f, 0f, true, true, 18f, 12f); arcTo(2.5f, 2.5f, 0f, true, true, 13f, 12f)
        moveTo(8f, 17f); arcTo(2.5f, 2.5f, 0f, true, true, 13f, 17f); arcTo(2.5f, 2.5f, 0f, true, true, 8f, 17f)
    }
    val Info = line("Info") {
        moveTo(3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        moveTo(12f, 8f); lineTo(12f, 8.01f)
        moveTo(12f, 11f); lineTo(12f, 16f)
    }
    val Gear = line("Gear", width = 1.8f) {
        // ring + centre
        moveTo(6f, 12f); arcTo(6f, 6f, 0f, true, true, 18f, 12f); arcTo(6f, 6f, 0f, true, true, 6f, 12f)
        moveTo(9.6f, 12f); arcTo(2.4f, 2.4f, 0f, true, true, 14.4f, 12f); arcTo(2.4f, 2.4f, 0f, true, true, 9.6f, 12f)
        // 8 teeth
        moveTo(18.5f, 12f); lineTo(21f, 12f)
        moveTo(5.5f, 12f); lineTo(3f, 12f)
        moveTo(12f, 18.5f); lineTo(12f, 21f)
        moveTo(12f, 5.5f); lineTo(12f, 3f)
        moveTo(16.6f, 16.6f); lineTo(18.36f, 18.36f)
        moveTo(7.4f, 7.4f); lineTo(5.64f, 5.64f)
        moveTo(16.6f, 7.4f); lineTo(18.36f, 5.64f)
        moveTo(7.4f, 16.6f); lineTo(5.64f, 18.36f)
    }
    val Bell = line("Bell") {
        moveTo(6f, 16f)
        curveTo(7.5f, 14.5f, 7.5f, 12f, 7.5f, 10f)
        curveTo(7.5f, 6.9f, 9.5f, 5f, 12f, 5f)
        curveTo(14.5f, 5f, 16.5f, 6.9f, 16.5f, 10f)
        curveTo(16.5f, 12f, 16.5f, 14.5f, 18f, 16f)
        close()
        moveTo(10f, 19f); curveTo(10.3f, 20.2f, 13.7f, 20.2f, 14f, 19f)
    }
    val User = line("User") {
        moveTo(8f, 8f); arcTo(4f, 4f, 0f, true, true, 16f, 8f); arcTo(4f, 4f, 0f, true, true, 8f, 8f)
        moveTo(5f, 20f)
        curveTo(5f, 16f, 8f, 14f, 12f, 14f)
        curveTo(16f, 14f, 19f, 16f, 19f, 20f)
    }
    val History = line("History") {
        moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, true, 20f, 12f); arcTo(8f, 8f, 0f, true, true, 4f, 12f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }
    val Star = line("Star") {
        moveTo(12f, 3.2f)
        lineTo(14.7f, 9.0f); lineTo(21f, 9.6f); lineTo(16.2f, 13.9f)
        lineTo(17.7f, 20.1f); lineTo(12f, 16.8f); lineTo(6.3f, 20.1f)
        lineTo(7.8f, 13.9f); lineTo(3f, 9.6f); lineTo(9.3f, 9.0f)
        close()
    }
    val Home = line("Home") {
        moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f)
        moveTo(6f, 9.5f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 9.5f)
    }
    val VolumeHigh = line("VolumeHigh") {
        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
        moveTo(15.5f, 9f); curveTo(17.3f, 10.8f, 17.3f, 13.2f, 15.5f, 15f)
        moveTo(18f, 6.5f); curveTo(21.2f, 9.7f, 21.2f, 14.3f, 18f, 17.5f)
    }
    val VolumeMute = line("VolumeMute") {
        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
        moveTo(16f, 10f); lineTo(21f, 15f)
        moveTo(21f, 10f); lineTo(16f, 15f)
    }

    // ---- Map & trip ----
    val Navigation = line("Navigation") {
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(6f, 10f); lineTo(12f, 4f); lineTo(18f, 10f)
    }
    val MapPin = line("MapPin") {
        moveTo(12f, 21f)
        curveTo(12f, 21f, 5f, 14.5f, 5f, 9f)
        arcTo(7f, 7f, 0f, true, true, 19f, 9f)
        curveTo(19f, 14.5f, 12f, 21f, 12f, 21f)
        close()
        moveTo(9.5f, 9f); arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 9f); arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 9f)
    }
    val Flag = line("Flag") {
        moveTo(6f, 3f); lineTo(6f, 21f)
        moveTo(6f, 4.5f); lineTo(17f, 4.5f); lineTo(14f, 8.5f); lineTo(17f, 12.5f); lineTo(6f, 12.5f)
    }
    val Gps = line("Gps") {
        moveTo(6f, 12f); arcTo(6f, 6f, 0f, true, true, 18f, 12f); arcTo(6f, 6f, 0f, true, true, 6f, 12f)
        moveTo(12f, 12f); lineTo(12f, 12.01f)
        moveTo(12f, 2.5f); lineTo(12f, 5f)
        moveTo(12f, 19f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(5f, 12f)
        moveTo(19f, 12f); lineTo(21.5f, 12f)
    }
    val Layers = line("Layers") {
        moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
        moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f)
        moveTo(3f, 16f); lineTo(12f, 21f); lineTo(21f, 16f)
    }

    // ---- Road-safety domain ----
    val Radar = line("Radar", width = 1.9f) {
        moveTo(3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        moveTo(12f, 12f); lineTo(12f, 12.01f)
        moveTo(12f, 12f); lineTo(18.4f, 5.6f)
    }
    val Camera = line("Camera", width = 1.9f) {
        moveTo(3f, 8f); lineTo(3f, 18f); lineTo(21f, 18f); lineTo(21f, 8f)
        lineTo(16.5f, 8f); lineTo(15f, 5.5f); lineTo(9f, 5.5f); lineTo(7.5f, 8f); close()
        moveTo(15.5f, 13f); arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 13f)
        arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 13f)
    }
    val Shield = line("Shield", width = 1.9f) {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11.5f)
        curveTo(19f, 16f, 16f, 19f, 12f, 21f)
        curveTo(8f, 19f, 5f, 16f, 5f, 11.5f)
        lineTo(5f, 6f); close()
    }
    val Warning = line("Warning", width = 1.9f) {
        moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
        moveTo(12f, 9f); lineTo(12f, 14f)
        moveTo(12f, 16.8f); lineTo(12f, 16.9f)
    }
    val Construction = line("Construction", width = 1.9f) {
        moveTo(9f, 20f); lineTo(11f, 6f); lineTo(13f, 6f); lineTo(15f, 20f); close()
        moveTo(6f, 20f); lineTo(18f, 20f)
        moveTo(10.2f, 13f); lineTo(13.8f, 13f)
        moveTo(10.6f, 9.5f); lineTo(13.4f, 9.5f)
    }
    val Accident = line("Accident", width = 1.9f) {
        // car body + cabin + wheels
        moveTo(4f, 16f); lineTo(5.5f, 11f); lineTo(8f, 9.5f); lineTo(16f, 9.5f); lineTo(18.5f, 11f); lineTo(20f, 16f)
        lineTo(20f, 17.5f); lineTo(4f, 17.5f); close()
        moveTo(8f, 9.5f); lineTo(9f, 7f); lineTo(15f, 7f); lineTo(16f, 9.5f)
        moveTo(7.5f, 17.5f); lineTo(7.5f, 19f)
        moveTo(16.5f, 17.5f); lineTo(16.5f, 19f)
    }
    val RadarCar = line("RadarCar", width = 1.8f) {
        // car body + wheels
        moveTo(3f, 17f); lineTo(4.3f, 13f); lineTo(6.5f, 11.7f); lineTo(13.5f, 11.7f); lineTo(15.7f, 13f); lineTo(17f, 17f)
        lineTo(17f, 18.3f); lineTo(3f, 18.3f); close()
        moveTo(6.5f, 18.3f); lineTo(6.5f, 19.6f)
        moveTo(13.5f, 18.3f); lineTo(13.5f, 19.6f)
        // radar waves
        moveTo(17.5f, 9.5f); arcTo(3f, 3f, 0f, false, true, 20.5f, 12.5f)
        moveTo(17.5f, 6.5f); arcTo(6f, 6f, 0f, false, true, 23.5f, 12.5f)
    }
    val Toll = line("Toll", width = 1.9f) {
        moveTo(5f, 21f); lineTo(5f, 7f)
        moveTo(3f, 21f); lineTo(7f, 21f)
        moveTo(5f, 8f); lineTo(21f, 12f)
        moveTo(9f, 9f); lineTo(9.6f, 11.1f)
        moveTo(13f, 10f); lineTo(13.6f, 12.1f)
        moveTo(17f, 11f); lineTo(17.6f, 13.1f)
    }

    // ---- Guidage : manœuvres (flèches) ----
    val ManeuverStraight = line("ManeuverStraight", width = 2.2f) {
        moveTo(12f, 21f); lineTo(12f, 4.5f)
        moveTo(6.5f, 10f); lineTo(12f, 4.5f); lineTo(17.5f, 10f)
    }
    val ManeuverRight = line("ManeuverRight", width = 2.2f) {
        moveTo(7f, 21f); lineTo(7f, 11f); lineTo(15.5f, 11f)
        moveTo(11f, 6.5f); lineTo(15.5f, 11f); lineTo(11f, 15.5f)
    }
    val ManeuverLeft = line("ManeuverLeft", width = 2.2f) {
        moveTo(17f, 21f); lineTo(17f, 11f); lineTo(8.5f, 11f)
        moveTo(13f, 6.5f); lineTo(8.5f, 11f); lineTo(13f, 15.5f)
    }
    val ManeuverSlightRight = line("ManeuverSlightRight", width = 2.2f) {
        moveTo(8f, 21f); lineTo(8f, 13.5f); lineTo(15.5f, 6f)
        moveTo(10.5f, 6f); lineTo(15.5f, 6f); lineTo(15.5f, 11f)
    }
    val ManeuverSlightLeft = line("ManeuverSlightLeft", width = 2.2f) {
        moveTo(16f, 21f); lineTo(16f, 13.5f); lineTo(8.5f, 6f)
        moveTo(13.5f, 6f); lineTo(8.5f, 6f); lineTo(8.5f, 11f)
    }
    val ManeuverSharpRight = line("ManeuverSharpRight", width = 2.2f) {
        moveTo(8f, 21f); lineTo(8f, 11.5f); lineTo(15.5f, 16f)
        moveTo(15.5f, 11f); lineTo(15.5f, 16f); lineTo(10.5f, 16f)
    }
    val ManeuverSharpLeft = line("ManeuverSharpLeft", width = 2.2f) {
        moveTo(16f, 21f); lineTo(16f, 11.5f); lineTo(8.5f, 16f)
        moveTo(8.5f, 11f); lineTo(8.5f, 16f); lineTo(13.5f, 16f)
    }
    val ManeuverUturn = line("ManeuverUturn", width = 2.2f) {
        moveTo(16.5f, 21f); lineTo(16.5f, 11f)
        curveTo(16.5f, 7f, 7.5f, 7f, 7.5f, 11f)
        lineTo(7.5f, 16.5f)
        moveTo(4.5f, 13.5f); lineTo(7.5f, 16.5f); lineTo(10.5f, 13.5f)
    }
    val ManeuverMerge = line("ManeuverMerge", width = 2.2f) {
        moveTo(12f, 21f); lineTo(12f, 5f)
        moveTo(8f, 9f); lineTo(12f, 5f); lineTo(16f, 9f)
        moveTo(18f, 20f); curveTo(18f, 14f, 12f, 14f, 12f, 10.5f)
    }
    val ManeuverRoundabout = line("ManeuverRoundabout", width = 2f) {
        moveTo(12f, 21f); lineTo(12f, 15.5f)
        moveTo(8f, 11.5f); arcTo(4f, 4f, 0f, true, true, 16f, 11.5f)
        arcTo(4f, 4f, 0f, true, true, 8f, 11.5f)
        moveTo(15.2f, 8.3f); lineTo(19.5f, 4.5f)
        moveTo(15.5f, 8f); lineTo(19.5f, 4.5f); lineTo(19f, 8.7f)
    }
}

private fun line(
    name: String,
    width: Float = 2f,
    block: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}.build()
