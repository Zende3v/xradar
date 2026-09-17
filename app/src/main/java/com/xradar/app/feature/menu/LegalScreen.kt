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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.theme.XRadarTheme

/** The privacy policy, served by the backend machine (port 9020). */
const val PRIVACY_POLICY_URL = "http://45.80.23.8:9020/"

/**
 * Mentions légales: the privacy policy, and where the map, the places and the figures come from.
 * The map keeps MapLibre's own attribution button as well.
 */
@Composable
fun LegalRoute(onBack: () -> Unit) {
    val spacing = XRadarTheme.spacing
    XRadarScreenScaffold(title = "Mentions légales", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            XRadarListGroup {
                Source("Politique de confidentialité", "Données collectées, durées de conservation et droits (RGPD)", PRIVACY_POLICY_URL)
            }

            XRadarListGroup(title = "Carte") {
                Source("Stadia Maps", "Tuiles vectorielles, polices et pictogrammes du fond de carte", "https://stadiamaps.com/attribution/")
                RowDivider()
                Source("OpenMapTiles", "Schéma des tuiles vectorielles", "https://openmaptiles.org/")
                RowDivider()
                Source("© contributeurs OpenStreetMap", "Carte, services autour, horaires, signalisation et limitations (ODbL)", "https://www.openstreetmap.org/copyright")
            }

            XRadarListGroup(title = "Autres") {
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
    XRadarListRow(
        title = title,
        subtitle = detail,
        onClick = { runCatching { uri.openUri(url) } },
    )
}

@Composable
private fun RowDivider() = XRadarDivider(Modifier.padding(start = XRadarTheme.spacing.lg))
