package com.xradar.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.data.preferences.AlertPreferences
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarSwitch
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiagnostic: () -> Unit,
) {
    SettingsScreen(
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onOpenHistory = onOpenHistory,
        onOpenDiagnostic = onOpenDiagnostic,
    )
}

/** Réglages : compte + infos app. Les alertes se configurent depuis le menu « Options » du HUD. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiagnostic: () -> Unit,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing

    XRadarScreenScaffold(title = "Réglages", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Spacer(Modifier.height(spacing.xs))

            XRadarListGroup(title = "Alertes") {
                AlertRadiusRow()
            }

            XRadarListGroup(title = "Communauté") {
                LiveSettings()
            }

            XRadarListGroup(title = "Compte") {
                NavRow("Profil", XRadarIcons.User, colors.accent, onOpenProfile)
                RowDivider()
                NavRow("Historique des trajets", XRadarIcons.History, colors.textSecondary, onOpenHistory)
            }

            XRadarListGroup(title = "À propos") {
                XRadarListRow(
                    title = "Version",
                    trailing = {
                        XRadarText("0.1.0", style = XRadarTheme.typography.callout, color = colors.textTertiary)
                    },
                )
            }

            XRadarListGroup(title = "Développeur") {
                NavRow("Diagnostic backend", XRadarIcons.Info, colors.accent, onOpenDiagnostic)
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun AlertRadiusRow() {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XRadarText("Zone de chargement", style = XRadarTheme.typography.body, color = colors.textPrimary)
            XRadarText("${prefs.alertRadiusKm} km", style = XRadarTheme.typography.callout, color = colors.accent)
        }
        Slider(
            value = prefs.alertRadiusKm.toFloat(),
            onValueChange = { v ->
                AppPreferences.updateAlerts {
                    it.copy(alertRadiusKm = v.toInt().coerceIn(AlertPreferences.MIN_RADIUS_KM, AlertPreferences.MAX_RADIUS_KM))
                }
            },
            valueRange = AlertPreferences.MIN_RADIUS_KM.toFloat()..AlertPreferences.MAX_RADIUS_KM.toFloat(),
            steps = 48,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surfaceHigh,
            ),
        )
        XRadarText(
            "Rayon autour de toi où radars et signalements sont chargés (réduit auto en zone dense).",
            style = XRadarTheme.typography.footnote,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun LiveSettings() {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    XRadarListRow(
        title = "Visible par les autres",
        leadingIcon = XRadarIcons.User,
        leadingTint = colors.accent,
        onClick = { AppPreferences.updateAlerts { it.copy(liveVisible = !it.liveVisible) } },
        trailing = {
            XRadarSwitch(
                checked = prefs.liveVisible,
                onCheckedChange = { AppPreferences.updateAlerts { p -> p.copy(liveVisible = it) } },
            )
        },
    )
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XRadarText("Rayon des usagers", style = XRadarTheme.typography.body, color = colors.textPrimary)
            XRadarText("${prefs.liveRadiusKm} km", style = XRadarTheme.typography.callout, color = colors.accent)
        }
        Slider(
            value = prefs.liveRadiusKm.toFloat(),
            onValueChange = { v ->
                AppPreferences.updateAlerts {
                    it.copy(liveRadiusKm = v.toInt().coerceIn(AlertPreferences.MIN_LIVE_KM, AlertPreferences.MAX_LIVE_KM))
                }
            },
            valueRange = AlertPreferences.MIN_LIVE_KM.toFloat()..AlertPreferences.MAX_LIVE_KM.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surfaceHigh,
            ),
        )
    }
}

@Composable
private fun NavRow(title: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    XRadarListRow(
        title = title,
        leadingIcon = icon,
        leadingTint = tint,
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

@Composable
private fun RowDivider() {
    XRadarDivider(Modifier.padding(start = 58.dp))
}

@Preview(name = "Réglages · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun SettingsScreenPreview() {
    XRadarTheme(darkTheme = true) {
        SettingsScreen(onBack = {}, onOpenProfile = {}, onOpenHistory = {}, onOpenDiagnostic = {})
    }
}
