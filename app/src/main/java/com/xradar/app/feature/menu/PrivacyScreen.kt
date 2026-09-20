package com.xradar.app.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.preferences.AppSettings
import com.xradar.app.data.recents.RecentsRepository
import com.xradar.app.data.traffic.TrafficApi
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarSwitch
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlinx.coroutines.launch

@Composable
fun PrivacyRoute(onBack: () -> Unit) {
    PrivacyScreen(onBack = onBack)
}

/**
 * "Confidentialité", like iOS: what the app keeps and shares, one switch each, each read by the
 * system it controls (the search, DriveViewModel). Off stops it at once; what it had kept goes
 * where it can.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val spacing = XRadarTheme.spacing
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    XRadarScreenScaffold(title = "Confidentialité", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Spacer(Modifier.height(spacing.xs))

            Group(
                "Personnalisation",
                "Les destinations que tu choisis restent sur ce téléphone pour te les proposer dans la recherche (« Récents »). " +
                    "Désactivé : plus rien n'est gardé et la liste est effacée.",
            ) {
                Switch("Suggestions de trajets", settings.tripSuggestions, { s, on -> s.copy(tripSuggestions = on) }) {
                    RecentsRepository(context).clear()
                }
            }

            Group(
                "Trafic",
                "Ta participation à l'évitement des bouchons : sur une route à 70 km/h ou plus, quand tu roules nettement " +
                    "moins vite que la limite, l'app envoie la position, le sens et la vitesse de ce moment, sans lien avec ton " +
                    "compte, effacés après 30 minutes. À plusieurs, cela signale un bouchon aux autres ; seul, l'app te demande " +
                    "« Ralentissement du trafic ? ». Désactivé : ta position n'alimente plus le trafic partagé. Le trafic sur ton " +
                    "trajet et « Éviter les bouchons » restent disponibles.",
            ) {
                Switch("Aide au trafic partagé", settings.sharedTraffic, { s, on -> s.copy(sharedTraffic = on) }) {
                    // Its recent slowdowns leave the shared traffic too.
                    scope.launch { TrafficApi().dismissProbe(AccountRepository.token) }
                }
            }

            Group(
                "Statistiques",
                "Statistiques de conduite : tes trajets et ton temps de conduite, enregistrés sur ton compte (Menu ▸ " +
                    "Statistiques) ; désactivé, les prochains ne sont plus enregistrés. Présence et position : le serveur " +
                    "compte les apps ouvertes et les trajets en cours, et l'équipe XRadar voit où tu es pendant que l'app " +
                    "est ouverte ; les positions sont effacées au bout de 30 jours. Temps d'utilisation : le temps passé " +
                    "dans l'app s'ajoute à ton compte.",
            ) {
                Switch("Statistiques de conduite", settings.drivingStats, { s, on -> s.copy(drivingStats = on) })
                XRadarDivider(Modifier.padding(start = XRadarTheme.spacing.lg))
                Switch("Présence et position", settings.presence, { s, on -> s.copy(presence = on) })
                XRadarDivider(Modifier.padding(start = XRadarTheme.spacing.lg))
                Switch("Temps d'utilisation", settings.usageTime, { s, on -> s.copy(usageTime = on) })
            }

            XRadarListGroup {
                XRadarListRow(
                    title = "Politique de confidentialité",
                    leadingIcon = XRadarIcons.Info,
                    leadingTint = XRadarTheme.colors.accent,
                    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                )
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun Group(title: String, footer: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm)) {
        XRadarListGroup(title = title) { content() }
        XRadarText(
            footer,
            style = XRadarTheme.typography.footnote,
            color = XRadarTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = XRadarTheme.spacing.md),
        )
    }
}

/** One switch on a setting ([set] writes it); [onOff] runs when the driver turns it off. */
@Composable
private fun Switch(
    title: String,
    checked: Boolean,
    set: (AppSettings, Boolean) -> AppSettings,
    onOff: () -> Unit = {},
) {
    val change = { on: Boolean ->
        AppPreferences.updateSettings { set(it, on) }
        if (!on) onOff()
    }
    XRadarListRow(
        title = title,
        onClick = { change(!checked) },
        trailing = { XRadarSwitch(checked = checked, onCheckedChange = change) },
        modifier = Modifier.height(56.dp),
    )
}
