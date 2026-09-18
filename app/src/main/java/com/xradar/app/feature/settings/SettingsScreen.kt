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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** Réglages : apparence, dépassement de la limitation, partage des ralentissements et diagnostic admin.
 *  Les alertes affichées se configurent depuis le menu « Options » du HUD. */
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

            XRadarListGroup(title = "Trafic") {
                ShareSlowdownsSetting()
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

/** "Partager les ralentissements", anonymous; on by default. */
@Composable
private fun ShareSlowdownsSetting() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XRadarText(
                "Partager les ralentissements",
                style = XRadarTheme.typography.body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            XRadarSwitch(
                checked = settings.shareSlowdowns,
                onCheckedChange = { on -> AppPreferences.updateSettings { it.copy(shareSlowdowns = on) } },
            )
        }
        Spacer(Modifier.height(spacing.xs))
        XRadarText(
            "Sur une route à 70 km/h ou plus, quand tu roules nettement moins vite que la limite, l'app envoie la position, le sens et la vitesse de ce moment, sans lien avec ton compte, effacés après 30 minutes. À plusieurs, cela signale un bouchon ; seul, l'app te demande « Ralentissement du trafic ? ».",
            style = XRadarTheme.typography.footnote,
            color = colors.textTertiary,
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
