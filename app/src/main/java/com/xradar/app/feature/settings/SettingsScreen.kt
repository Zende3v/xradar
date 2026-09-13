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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.R
import com.xradar.app.data.preferences.AlertPreferences
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.preferences.MapStyle
import com.xradar.app.data.preferences.ThemeMode
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
    onOpenDiagnostic: () -> Unit,
) {
    SettingsScreen(
        onBack = onBack,
        onOpenDiagnostic = onOpenDiagnostic,
    )
}

/** Réglages : compte + infos app. Les alertes se configurent depuis le menu « Options » du HUD. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostic: () -> Unit,
) {
    val colors = XRadarTheme.colors
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
                RowDivider()
                MapStyleSetting()
            }

            XRadarListGroup(title = "Communauté") {
                LiveSettings()
            }

            if (account?.role == com.xradar.app.core.model.Role.Admin) {
                XRadarListGroup(title = "Développeur") {
                    NavRow(
                        "Diagnostic backend",
                        ImageVector.vectorResource(R.drawable.ic_diagnostic),
                        colors.accent,
                        onOpenDiagnostic,
                    )
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** App color scheme: follow the phone, or force one. */
@Composable
private fun ThemeSetting() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    Segmented(
        title = "Thème de l'app",
        options = listOf(
            "Système" to ThemeMode.System,
            "Clair" to ThemeMode.Light,
            "Sombre" to ThemeMode.Dark,
        ),
        selected = settings.themeMode,
        onSelect = { mode -> AppPreferences.updateSettings { it.copy(themeMode = mode) } },
    )
}

/** Basemap: follow the theme, or pin OSM Bright / Alidade Smooth Dark. */
@Composable
private fun MapStyleSetting() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    Segmented(
        title = "Fond de carte",
        options = listOf(
            "Auto" to MapStyle.Auto,
            "Clair" to MapStyle.Bright,
            "Sombre" to MapStyle.Dark,
        ),
        selected = settings.mapStyle,
        onSelect = { style -> AppPreferences.updateSettings { it.copy(mapStyle = style) } },
        hint = "Auto suit le jour et la nuit à ta position : OSM Bright de jour, Alidade Smooth Dark de nuit.",
    )
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
        SettingsScreen(onBack = {}, onOpenDiagnostic = {})
    }
}
