package com.xradar.app.designsystem.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarButton
import com.xradar.app.designsystem.component.XRadarButtonVariant
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarChip
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * On-device gallery of the component layer, showing each component with its
 * states and real (fictional) data. The state below is live — buttons and chips
 * actually react — so interactions can be felt, not just seen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentGallery() {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    var trips by remember { mutableIntStateOf(0) }
    var filterOn by remember { mutableStateOf(false) }

    ShowcaseScaffold {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            XRadarText("COMPOSANTS", style = XRadarTheme.typography.caption, color = colors.accent)
            XRadarText("Bibliothèque UI", style = XRadarTheme.typography.display, color = colors.textPrimary)
            XRadarText(
                "Composants réutilisables, chacun avec ses états. Données fictives.",
                style = XRadarTheme.typography.callout,
                color = colors.textSecondary,
            )
        }

        ShowcaseSection("Boutons") {
            XRadarButton(
                "Démarrer le trajet",
                onClick = { trips++ },
                leadingIcon = XRadarIcons.Navigation,
                fillWidth = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                XRadarButton("Secondaire", onClick = {}, variant = XRadarButtonVariant.Secondary, modifier = Modifier.weight(1f))
                XRadarButton("Ghost", onClick = {}, variant = XRadarButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                XRadarButton("Supprimer", onClick = {}, variant = XRadarButtonVariant.Destructive, modifier = Modifier.weight(1f))
                XRadarButton("Désactivé", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
            }
            XRadarButton("Recherche…", onClick = {}, loading = true, variant = XRadarButtonVariant.Secondary, fillWidth = true)
        }

        ShowcaseSection("Cartes") {
            XRadarCard {
                XRadarText("Trajet en cours", style = XRadarTheme.typography.headline, color = colors.textPrimary)
                XRadarText("Autoroute A7 · 12,4 km restants", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
                XRadarText("Trajets démarrés : $trips", style = XRadarTheme.typography.footnote, color = colors.textTertiary)
            }
            XRadarCard(onClick = {}) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        XRadarText("Historique des trajets", style = XRadarTheme.typography.headline, color = colors.textPrimary)
                        XRadarText("128 trajets enregistrés", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
                    }
                    XRadarIcon(XRadarIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
                }
            }
        }

        ShowcaseSection("Chips") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                XRadarChip("Radar mobile", dotColor = colors.radarMobile, trailing = "1,2 km")
                XRadarChip("Zone de contrôle", dotColor = colors.controlZone, trailing = "signalé 18:32")
                XRadarChip("Danger · accident", dotColor = colors.hazard, trailing = "600 m")
                XRadarChip(
                    if (filterOn) "Filtre actif" else "Activer le filtre",
                    selected = filterOn,
                    onClick = { filterOn = !filterOn },
                )
            }
        }

        ShowcaseSection("Badges") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                XRadarBadge("Nouveau", color = colors.accent)
                XRadarBadge("Radar", color = colors.danger)
                XRadarBadge("Fiable 92%", color = colors.success)
                XRadarBadge("Contrôle", color = colors.controlZone)
            }
        }

        ShowcaseSection("Icônes") {
            val icons = listOf(
                "arrowLeft" to XRadarIcons.ArrowLeft,
                "chevronL" to XRadarIcons.ChevronLeft,
                "chevronR" to XRadarIcons.ChevronRight,
                "chevronUp" to XRadarIcons.ChevronUp,
                "chevronDn" to XRadarIcons.ChevronDown,
                "close" to XRadarIcons.Close,
                "check" to XRadarIcons.Check,
                "plus" to XRadarIcons.Plus,
                "minus" to XRadarIcons.Minus,
                "menu" to XRadarIcons.Menu,
                "more" to XRadarIcons.More,
                "search" to XRadarIcons.Search,
                "settings" to XRadarIcons.Settings,
                "info" to XRadarIcons.Info,
                "bell" to XRadarIcons.Bell,
                "user" to XRadarIcons.User,
                "history" to XRadarIcons.History,
                "star" to XRadarIcons.Star,
                "home" to XRadarIcons.Home,
                "volume" to XRadarIcons.VolumeHigh,
                "mute" to XRadarIcons.VolumeMute,
                "navigation" to XRadarIcons.Navigation,
                "mapPin" to XRadarIcons.MapPin,
                "flag" to XRadarIcons.Flag,
                "gps" to XRadarIcons.Gps,
                "radar" to XRadarIcons.Radar,
                "camera" to XRadarIcons.Camera,
                "shield" to XRadarIcons.Shield,
                "warning" to XRadarIcons.Warning,
                "cone" to XRadarIcons.Construction,
                "toll" to XRadarIcons.Toll,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                icons.forEach { (name, icon) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        XRadarIcon(icon, contentDescription = name, tint = colors.textSecondary)
                        XRadarText(name, style = XRadarTheme.typography.caption, color = colors.textTertiary)
                    }
                }
            }
        }

        ShowcaseSection("Séparateur") {
            XRadarDivider()
        }
    }
}

@Preview(name = "Composants · dark", showBackground = true, backgroundColor = 0xFF06070A)
@Composable
private fun ComponentGalleryDarkPreview() {
    XRadarTheme(darkTheme = true) { ComponentGallery() }
}
