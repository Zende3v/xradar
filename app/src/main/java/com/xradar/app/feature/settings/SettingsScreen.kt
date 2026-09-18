package com.xradar.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.R
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.preferences.AppTheme
import com.xradar.app.data.preferences.OverspeedWarning
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
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
    val spacing = XRadarTheme.spacing
    val account by com.xradar.app.data.account.AccountRepository.account.collectAsStateWithLifecycle()

    XRadarScreenScaffold(title = "Réglages", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Spacer(Modifier.height(spacing.xs))

            XRadarListGroup(title = "Apparence") {
                ThemeSetting()
            }

            XRadarListGroup(title = "Alertes") {
                OverspeedSetting()
            }

            XRadarListGroup(title = "Volume") {
                val alerts by AppPreferences.alerts.collectAsStateWithLifecycle()
                VolumeSetting("Volume Guidage", alerts.guidanceVolume) { v -> AppPreferences.updateAlerts { it.copy(guidanceVolume = v) } }
                VolumeSetting("Volume alertes", alerts.alertVolume) { v -> AppPreferences.updateAlerts { it.copy(alertVolume = v) } }
                XRadarText(
                    "Guidage : les consignes de navigation. Alertes : les sons et les annonces des radars, des dangers et du " +
                        "dépassement. Chacun indépendant de l'autre, dans la limite du volume du téléphone.",
                    style = XRadarTheme.typography.footnote,
                    color = XRadarTheme.colors.textTertiary,
                    modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.md),
                )
            }

            if (account?.role == com.xradar.app.core.model.Role.Admin) {
                XRadarListGroup(title = "Développeur") {
                    NavRow("Diagnostic backend", ImageVector.vectorResource(R.drawable.ic_diagnostic), onOpenDiagnostic)
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
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
        hint = "L'app, la carte et le HUD ensemble. Auto suit le jour et la nuit à ta position : clair de jour, sombre de nuit.",
    )
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
        hint = "Plus de 5 km/h au-dessus de la limite, puis un rappel par minute tant que ça dure. Vocal suit le bouton des annonces vocales, Bip celui du son.",
    )
}

/** A volume from 0 to 100 %, saved when the finger lets go (not at every step of the drag). */
@Composable
private fun VolumeSetting(title: String, stored: Float, onCommit: (Float) -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    var value by remember(stored) { mutableFloatStateOf(stored) }
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XRadarText(title, style = XRadarTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            XRadarText("${(value * 100).roundToInt()} %", style = XRadarTheme.typography.callout, color = colors.textSecondary)
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
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        XRadarText(title, style = XRadarTheme.typography.body, color = colors.textPrimary)
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            options.forEach { (label, value) ->
                val on = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(XRadarTheme.shapes.md)
                        .background(if (on) colors.accent.copy(alpha = 0.18f) else colors.surface)
                        .border(1.dp, if (on) colors.accent else colors.border, XRadarTheme.shapes.md)
                        .clickable { onSelect(value) }
                        .padding(vertical = spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    XRadarText(
                        label,
                        style = XRadarTheme.typography.callout,
                        color = if (on) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
        if (hint != null) {
            Spacer(Modifier.height(spacing.xs))
            XRadarText(hint, style = XRadarTheme.typography.footnote, color = colors.textTertiary)
        }
    }
}

@Composable
private fun NavRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    XRadarListRow(
        title = title,
        leadingIcon = icon,
        glow = true,
        onClick = onClick,
        trailing = {
            XRadarIcon(
                XRadarIcons.ChevronRight,
                contentDescription = null,
                tint = XRadarTheme.colors.textTertiary,
                size = 20.dp,
            )
        },
    )
}

@Preview(name = "Réglages · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun SettingsScreenPreview() {
    XRadarTheme(darkTheme = true) {
        SettingsScreen(onBack = {}, onOpenDiagnostic = {})
    }
}
