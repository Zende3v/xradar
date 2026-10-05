package com.eona.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.R
import com.eona.app.core.model.FuelType
import com.eona.app.data.preferences.AccentColor
import com.eona.app.data.preferences.CONSUMPTION_RANGE
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.preferences.AppTheme
import com.eona.app.data.preferences.OverspeedWarning
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaSwitch
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDiagnostic: () -> Unit,
) {
    SettingsScreen(
        onBack = onBack,
        onOpenDiagnostic = onOpenDiagnostic,
    )
}

/** Réglages : apparence, dépassement de la limitation, les deux volumes et diagnostic admin.
 *  Les alertes affichées se configurent depuis le menu « Options » du HUD ; ce que l'app garde et
 *  partage, depuis Menu ▸ Confidentialité. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostic: () -> Unit,
) {
    val spacing = EonaTheme.spacing
    val account by com.eona.app.data.account.AccountRepository.account.collectAsStateWithLifecycle()

    EonaScreenScaffold(title = "Réglages", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Spacer(Modifier.height(spacing.xs))

            EonaListGroup(title = "Apparence") {
                ThemeSetting()
                AccentSetting()
            }

            val settings by AppPreferences.settings.collectAsStateWithLifecycle()
            SettingsGroup("Véhicule", "Protection pluie : écran verrouillé dès 15 km/h.") {
                VehiclePicker(settings.vehicleType) { type -> AppPreferences.updateSettings { it.copy(vehicleType = type) } }
                SettingSwitch("Protection pluie", settings.rainLock) { on -> AppPreferences.updateSettings { it.copy(rainLock = on) } }
            }

            SettingsGroup("Carburant", "Filtre des stations proches. Coût estimé des trajets.") {
                Segmented(
                    title = "Carburant préféré",
                    options = FuelType.entries.map { it.label to it },
                    selected = settings.preferredFuel,
                    onSelect = { fuel -> AppPreferences.updateSettings { it.copy(preferredFuel = fuel, fuelNearestOnly = false) } },
                    perRow = 3,
                )
                ConsumptionSetting(settings.consumption)
            }

            SettingsGroup("Conduite", "110 km/h sur autoroute, 100 sur voie rapide, 80 sur route.") {
                SettingSwitch("Permis probatoire", settings.probationary) { on -> AppPreferences.updateSettings { it.copy(probationary = on) } }
            }

            EonaListGroup(title = "Alertes") {
                OverspeedSetting()
            }

            EonaListGroup(title = "Volume") {
                val alerts by AppPreferences.alerts.collectAsStateWithLifecycle()
                VolumeSetting("Volume Guidage", alerts.guidanceVolume) { v -> AppPreferences.updateAlerts { it.copy(guidanceVolume = v) } }
                VolumeSetting("Volume alertes", alerts.alertVolume) { v -> AppPreferences.updateAlerts { it.copy(alertVolume = v) } }
            }

            if (account?.role == com.eona.app.core.model.Role.Admin) {
                EonaListGroup(title = "Développeur") {
                    NavRow("Diagnostic backend", ImageVector.vectorResource(R.drawable.ic_diagnostic), onOpenDiagnostic)
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** Un groupe et sa note dessous (iOS : footer de section). */
@Composable
private fun SettingsGroup(title: String, footer: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
        EonaListGroup(title = title) { content() }
        EonaText(
            footer,
            style = EonaTheme.typography.footnote,
            color = EonaTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = EonaTheme.spacing.md),
        )
    }
}

/** Un interrupteur de réglage. */
@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    EonaListRow(
        title = title,
        onClick = { onChange(!checked) },
        trailing = { EonaSwitch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.height(56.dp),
    )
}

/** "Thème général": the app, the map and the HUD together; "Auto" by day and night. */
@Composable
private fun ThemeSetting() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    Segmented(
        title = "Thème général",
        options = listOf(
            "Auto" to AppTheme.Auto,
            "Jour" to AppTheme.Day,
            "Nuit" to AppTheme.Night,
        ),
        selected = settings.theme,
        onSelect = { theme -> AppPreferences.updateSettings { it.copy(theme = theme) } },
        hint = "Auto : clair de jour, sombre de nuit.",
    )
}

/** « Couleur de l'app » : boutons, tracé du trajet, détails. La palette en piste, un cran par teinte. */
@Composable
private fun AccentSetting() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EonaText("Couleur de l'app", style = EonaTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            EonaText(settings.accent.label, style = EonaTheme.typography.callout, color = colors.textSecondary)
        }
        Spacer(Modifier.height(spacing.sm))
        AccentSlider(settings.accent) { colour -> AppPreferences.updateSettings { it.copy(accent = colour) } }
    }
}

/** Glisser ou toucher choisit la teinte sous le doigt ; pastille blanche cerclée sur la piste. */
@Composable
private fun AccentSlider(selection: AccentColor, onPick: (AccentColor) -> Unit) {
    val palette = AccentColor.entries
    val haptic = LocalHapticFeedback.current
    val pick = rememberUpdatedState { x: Float, width: Int ->
        if (width > 0) {
            val picked = palette[(x / width * palette.size).toInt().coerceIn(0, palette.size - 1)]
            if (picked != selection) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPick(picked)
            }
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACCENT_THUMB + 4.dp)
            .pointerInput(Unit) {
                detectTapGestures { pick.value(it.x, size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> pick.value(change.position.x, size.width) }
            }
            .semantics { contentDescription = "Couleur de l'app, ${selection.label}" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape),
        ) {
            palette.forEach { colour ->
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF000000.toInt() or colour.rgb)))
            }
        }
        val step = maxWidth / palette.size
        val offset by animateDpAsState(step * (palette.indexOf(selection) + 0.5f) - ACCENT_THUMB / 2, label = "accent")
        Box(
            Modifier
                .offset(x = offset)
                .size(ACCENT_THUMB)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF000000.toInt() or selection.rgb))
                .border(3.dp, Color.White, CircleShape),
        )
    }
}

private val ACCENT_THUMB = 30.dp

/** « Consommation » : 1,0 à 30,0 L/100 km, cran de 0,1, enregistrée au lâcher. */
@Composable
private fun ConsumptionSetting(stored: Double) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var value by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    val rounded = (value * 10).roundToInt() / 10.0
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EonaText("Consommation", style = EonaTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            EonaText(
                "${"%.1f".format(rounded).replace('.', ',')} L/100 km",
                style = EonaTheme.typography.callout,
                color = colors.textSecondary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { AppPreferences.updateSettings { it.copy(consumption = rounded) } },
            valueRange = CONSUMPTION_RANGE.start.toFloat()..CONSUMPTION_RANGE.endInclusive.toFloat(),
            // Cran de 0,1 : 290 valeurs, 288 crans entre les bouts.
            steps = 288,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surfaceHigh,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** "Dépassement limitation": spoken, a beep of its own, or nothing. */
@Composable
private fun OverspeedSetting() {
    val alerts by AppPreferences.alerts.collectAsStateWithLifecycle()
    Segmented(
        title = "Dépassement limitation",
        options = listOf(
            "Vocal" to OverspeedWarning.Voice,
            "Bip" to OverspeedWarning.Beep,
            "Aucun" to OverspeedWarning.Off,
        ),
        selected = alerts.overspeed,
        onSelect = { warning -> AppPreferences.updateAlerts { it.copy(overspeed = warning) } },
        hint = "Au-delà de 5 km/h, rappel chaque minute.",
    )
}

/** A volume from 0 to 100 %, saved when the finger lets go (not at every step of the drag). */
@Composable
private fun VolumeSetting(title: String, stored: Float, onCommit: (Float) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var value by remember(stored) { mutableFloatStateOf(stored) }
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EonaText(title, style = EonaTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            EonaText("${(value * 100).roundToInt()} %", style = EonaTheme.typography.callout, color = colors.textSecondary)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = 0f..1f,
            // 5 % steps, as on iOS.
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surfaceHigh,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** Small pill picker — one row, one choice, no Material segmented button. */
@Composable
private fun <T> Segmented(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    hint: String? = null,
    /** Pastilles par ligne : six carburants tiennent en deux lignes de trois. */
    perRow: Int = options.size,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        EonaText(title, style = EonaTheme.typography.body, color = colors.textPrimary)
        Spacer(Modifier.height(spacing.sm))
        options.chunked(perRow).forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            row.forEach { (label, value) ->
                val on = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(EonaTheme.shapes.md)
                        .background(if (on) colors.accent.copy(alpha = 0.18f) else colors.surface)
                        .border(1.dp, if (on) colors.accent else colors.border, EonaTheme.shapes.md)
                        .clickable { onSelect(value) }
                        .padding(vertical = spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    EonaText(
                        label,
                        style = EonaTheme.typography.callout,
                        color = if (on) colors.accent else colors.textSecondary,
                    )
                }
            }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
        }
        if (hint != null) {
            Spacer(Modifier.height(spacing.xs))
            EonaText(hint, style = EonaTheme.typography.footnote, color = colors.textTertiary)
        }
    }
}

@Composable
private fun NavRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    EonaListRow(
        title = title,
        leadingIcon = icon,
        glow = true,
        onClick = onClick,
        trailing = {
            EonaIcon(
                EonaIcons.ChevronRight,
                contentDescription = null,
                tint = EonaTheme.colors.textTertiary,
                size = 20.dp,
            )
        },
    )
}

@Preview(name = "Réglages · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun SettingsScreenPreview() {
    EonaTheme(darkTheme = true) {
        SettingsScreen(onBack = {}, onOpenDiagnostic = {})
    }
}
