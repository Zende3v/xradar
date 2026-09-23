package com.eona.app.feature.menu

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.feature.onboarding.TermsTextScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.theme.EonaTheme

/** The privacy policy, served by the backend machine through Cloudflare Tunnel. */
const val PRIVACY_POLICY_URL = "https://confidentialite.zylo-app.fr/"

/**
 * "À propos": the privacy policy, and where the map, the places and the figures come from.
 * The map keeps MapLibre's own attribution button as well.
 */
@Composable
fun LegalRoute(onBack: () -> Unit) {
    val spacing = EonaTheme.spacing
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    var termsOpen by remember { mutableStateOf(false) }
    if (termsOpen) {
        TermsTextScreen(onClose = { termsOpen = false })
        return
    }
    // "Version 1.1 · acceptées le 22/09/2026", or what the text is.
    val termsSubtitle = when {
        settings.termsVersion.isEmpty() -> "Le texte qui encadre l'usage d'EONA"
        settings.termsAcceptedAt == null -> "Version ${settings.termsVersion} acceptée"
        else -> "Version ${settings.termsVersion} · acceptées le " +
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(settings.termsAcceptedAt!!))
    }
    EonaScreenScaffold(title = "À propos", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            EonaListGroup {
                Source("Politique de confidentialité", "Données collectées, durées de conservation et droits (RGPD)", PRIVACY_POLICY_URL)
                RowDivider()
                EonaListRow(
                    title = "Conditions générales d'utilisation",
                    subtitle = termsSubtitle,
                    onClick = { termsOpen = true },
                )
            }

            EonaListGroup(title = "Carte") {
                Source("Stadia Maps", "Tuiles vectorielles, polices et pictogrammes du fond de carte", "https://stadiamaps.com/attribution/")
                RowDivider()
                Source("OpenMapTiles", "Schéma des tuiles vectorielles", "https://openmaptiles.org/")
                RowDivider()
                Source("© contributeurs OpenStreetMap", "Carte, services autour, horaires, signalisation et limitations (ODbL)", "https://www.openstreetmap.org/copyright")
            }

            EonaListGroup(title = "Autres") {
                Source("prix-carburants.gouv.fr", "Prix officiels des carburants", "https://www.prix-carburants.gouv.fr/")
                RowDivider()
                Source("Base Adresse Nationale", "Recherche d'adresses", "https://adresse.data.gouv.fr/")
                RowDivider()
                Source("openrouteservice", "Calcul des itinéraires (© HeiGIT, données OpenStreetMap)", "https://openrouteservice.org/")
                RowDivider()
                Source("data.gouv.fr", "Position des radars automatiques", "https://www.data.gouv.fr/")
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun Source(title: String, detail: String, url: String) {
    val uri = LocalUriHandler.current
    EonaListRow(
        title = title,
        subtitle = detail,
        onClick = { runCatching { uri.openUri(url) } },
    )
}

@Composable
private fun RowDivider() = EonaDivider(Modifier.padding(start = EonaTheme.spacing.lg))
