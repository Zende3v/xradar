package com.eona.app.feature.drive

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.core.geo.Geo
import com.eona.app.core.model.ParkedVehicle
import com.eona.app.core.model.ParkingSpot
import com.eona.app.core.model.RouteChoiceText
import com.eona.app.data.parking.ParkingRepository
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.location.LocationRepository
import kotlinx.coroutines.delay

/** Bleu acier des repères de stationnement, sur la carte et dans la feuille (iOS parkingBlue). */
val ParkingBlue = Color(0xFF3A6EA5)

/** Pictogramme du véhicule garé. */
val ParkedVehicle.icon: ImageVector
    get() = when (this) {
        ParkedVehicle.Car -> EonaIcons.ParkCar
        ParkedVehicle.Motorcycle -> EonaIcons.ParkMoto
        ParkedVehicle.Bicycle -> EonaIcons.Bicycle
        ParkedVehicle.Scooter -> EonaIcons.KickScooter
    }

/**
 * « Stationnement » (iOS ParkingSheet) : repères posés, du plus récent au plus ancien. « Garer
 * ici » en pose un ; un repère ouvre sa fiche : véhicule, trajet à pied, retrait. [focus] :
 * repère touché sur la carte, sa fiche d'abord. [vehicle] : véhicule d'un nouveau repère.
 */
@Composable
fun ParkingSheet(vehicle: ParkedVehicle, focus: String?, onClose: () -> Unit) {
    val spots by ParkingRepository.spots.collectAsStateWithLifecycle()
    var selected by remember(focus) { mutableStateOf(focus) }
    var noFix by remember { mutableStateOf(false) }
    // L'âge des repères avance seul, feuille ouverte.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    DriveSheet(onDismiss = onClose) {
        val spot = selected?.let { id -> spots.firstOrNull { it.id == id } }
        if (spot != null) {
            SheetBackTitle(title = "Véhicule garé") { selected = null }
            SpotLine(spot, detailText(spot, now), chevron = false)
            VehicleRow(spot.vehicle) { ParkingRepository.setVehicle(it, spot.id) }
            Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
                EonaButton("Y aller à pied", {
                    // Itinéraire à pied jusqu'au repère, dans l'app de cartes du téléphone.
                    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${spot.lat},${spot.lon}&travelmode=walking")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, leadingIcon = EonaIcons.Walk, fillWidth = true)
                EonaButton("Retirer le repère", {
                    ParkingRepository.remove(spot.id)
                    if (ParkingRepository.spots.value.isEmpty()) onClose() else selected = null
                }, variant = EonaButtonVariant.Secondary, fillWidth = true)
            }
        } else {
            EonaText("Stationnement", style = EonaTheme.typography.title, color = EonaTheme.colors.textPrimary)
            if (spots.isEmpty()) {
                EonaText("Aucun repère.", style = EonaTheme.typography.body, color = EonaTheme.colors.textSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
                    spots.forEach { s ->
                        Box(Modifier.clip(EonaTheme.shapes.lg).clickable { selected = s.id }) {
                            SpotLine(s, detailText(s, now), chevron = true)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
                EonaButton("Garer ici", {
                    val fix = LocationRepository.location.value
                    if (fix == null) {
                        noFix = true
                    } else {
                        noFix = false
                        val parked = ParkingRepository.park(fix.latitude, fix.longitude, vehicle)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selected = parked.id
                    }
                }, leadingIcon = EonaIcons.Parking, fillWidth = true)
                if (noFix) {
                    EonaText(
                        "Position introuvable. Réessaie dans un instant.",
                        style = EonaTheme.typography.footnote,
                        color = EonaTheme.colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** "Garé il y a 12 min · à 350 m". */
private fun detailText(spot: ParkingSpot, now: Long): String {
    val age = "Garé ${ParkingSpot.ageLabel(spot.parkedAtMillis, now)}"
    val fix = LocationRepository.location.value ?: return age
    val meters = Geo.haversine(fix.latitude, fix.longitude, spot.lat, spot.lon)
    return "$age · à ${RouteChoiceText.distance(Math.round(meters).toInt())}"
}

/** Un repère : pastille du repère sur la carte, véhicule, âge et distance. */
@Composable
private fun SpotLine(spot: ParkingSpot, detail: String, chevron: Boolean) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.lg)
            .background(if (chevron) colors.surfaceHigh.copy(alpha = 0.6f) else Color.Transparent)
            .padding(if (chevron) spacing.sm else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(EonaTheme.shapes.md)
                .background(ParkingBlue),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(spot.vehicle.icon, contentDescription = null, tint = Color.White, size = 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText(spot.vehicle.label, style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
            EonaText(detail, style = EonaTheme.typography.footnote, color = colors.textSecondary)
        }
        if (chevron) EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, size = 18.dp)
    }
}

/** Voiture, moto, vélo, trottinette : le véhicule retenu en accent. */
@Composable
private fun VehicleRow(selected: ParkedVehicle, onSelect: (ParkedVehicle) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        ParkedVehicle.entries.forEach { vehicle ->
            val on = vehicle == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(EonaTheme.shapes.lg)
                    .background(if (on) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .border(if (on) 1.5.dp else 1.dp, if (on) colors.accent else colors.border, EonaTheme.shapes.lg)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(vehicle) }
                    .padding(vertical = spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                EonaIcon(vehicle.icon, contentDescription = null, tint = if (on) colors.accent else colors.textSecondary, size = 22.dp)
                EonaText(vehicle.label, style = EonaTheme.typography.caption, color = if (on) colors.accent else colors.textSecondary, maxLines = 1)
            }
        }
    }
}
