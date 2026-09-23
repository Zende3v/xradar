package com.eona.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.eona.app.R
import com.eona.app.data.preferences.AccentColor
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.preferences.AppTheme
import com.eona.app.data.preferences.OverspeedWarning
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
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

/** "Couleur de l'app": the tint of everything interactive, and of the route drawn on the map. */
@Composable
private fun AccentSetting() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        EonaText("Couleur de l'app", style = EonaTheme.typography.body, color = colors.textPrimary)
        Spacer(Modifier.height(spacing.sm))
        AccentColor.entries.chunked(ACCENTS_PER_ROW).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.padding(bottom = spacing.sm),
            ) {
                row.forEach { colour ->
                    val chosen = settings.accent == colour
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(Color(0xFF000000.toInt() or colour.rgb))
                            .border(if (chosen) 2.5.dp else 0.dp, colors.textPrimary, CircleShape)
                            .clickable { AppPreferences.updateSettings { it.copy(accent = colour) } },
                    )
                }
                // The last row keeps the size of the others.
                repeat(ACCENTS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        EonaText(
            "La teinte des boutons, du tracé du trajet et des détails de l'interface.",
            style = EonaTheme.typography.footnote,
            color = colors.textTertiary,
        )
    }
}

private const val ACCENTS_PER_ROW = 6

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
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        EonaText(title, style = EonaTheme.typography.body, color = colors.textPrimary)
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            options.forEach { (label, value) ->
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
