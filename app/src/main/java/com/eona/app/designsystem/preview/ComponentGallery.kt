package com.eona.app.designsystem.preview

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
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaChip
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * On-device gallery of the component layer, showing each component with its
 * states and real (fictional) data. The state below is live — buttons and chips
 * actually react — so interactions can be felt, not just seen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentGallery() {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var trips by remember { mutableIntStateOf(0) }
    var filterOn by remember { mutableStateOf(false) }

    ShowcaseScaffold {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            EonaText("COMPOSANTS", style = EonaTheme.typography.caption, color = colors.accent)
            EonaText("Bibliothèque UI", style = EonaTheme.typography.display, color = colors.textPrimary)
            EonaText(
                "Composants réutilisables, chacun avec ses états. Données fictives.",
                style = EonaTheme.typography.callout,
                color = colors.textSecondary,
            )
        }

        ShowcaseSection("Boutons") {
            EonaButton(
                "Démarrer le trajet",
                onClick = { trips++ },
                leadingIcon = EonaIcons.Navigation,
                fillWidth = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                EonaButton("Secondaire", onClick = {}, variant = EonaButtonVariant.Secondary, modifier = Modifier.weight(1f))
                EonaButton("Ghost", onClick = {}, variant = EonaButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                EonaButton("Supprimer", onClick = {}, variant = EonaButtonVariant.Destructive, modifier = Modifier.weight(1f))
                EonaButton("Désactivé", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
            }
            EonaButton("Recherche…", onClick = {}, loading = true, variant = EonaButtonVariant.Secondary, fillWidth = true)
        }

        ShowcaseSection("Cartes") {
            EonaCard {
                EonaText("Trajet en cours", style = EonaTheme.typography.headline, color = colors.textPrimary)
                EonaText("Autoroute A7 · 12,4 km restants", style = EonaTheme.typography.subhead, color = colors.textSecondary)
                EonaText("Trajets démarrés : $trips", style = EonaTheme.typography.footnote, color = colors.textTertiary)
            }
            EonaCard(onClick = {}) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        EonaText("Historique des trajets", style = EonaTheme.typography.headline, color = colors.textPrimary)
                        EonaText("128 trajets enregistrés", style = EonaTheme.typography.subhead, color = colors.textSecondary)
                    }
                    EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
                }
            }
        }

        ShowcaseSection("Chips") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                EonaChip("Radar mobile", dotColor = colors.radarMobile, trailing = "1,2 km")
                EonaChip("Zone de contrôle", dotColor = colors.controlZone, trailing = "signalé 18:32")
                EonaChip("Danger · accident", dotColor = colors.hazard, trailing = "600 m")
                EonaChip(
                    if (filterOn) "Filtre actif" else "Activer le filtre",
                    selected = filterOn,
                    onClick = { filterOn = !filterOn },
                )
            }
        }

        ShowcaseSection("Badges") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaBadge("Nouveau", color = colors.accent)
                EonaBadge("Radar", color = colors.danger)
                EonaBadge("Fiable 92%", color = colors.success)
                EonaBadge("Contrôle", color = colors.controlZone)
            }
        }

        ShowcaseSection("Icônes") {
            val icons = listOf(
                "arrowLeft" to EonaIcons.ArrowLeft,
                "chevronL" to EonaIcons.ChevronLeft,
                "chevronR" to EonaIcons.ChevronRight,
                "chevronUp" to EonaIcons.ChevronUp,
                "chevronDn" to EonaIcons.ChevronDown,
                "close" to EonaIcons.Close,
                "check" to EonaIcons.Check,
                "plus" to EonaIcons.Plus,
                "minus" to EonaIcons.Minus,
                "menu" to EonaIcons.Menu,
                "more" to EonaIcons.More,
                "search" to EonaIcons.Search,
                "settings" to EonaIcons.Settings,
                "info" to EonaIcons.Info,
                "bell" to EonaIcons.Bell,
                "user" to EonaIcons.User,
                "history" to EonaIcons.History,
                "star" to EonaIcons.Star,
                "home" to EonaIcons.Home,
                "volume" to EonaIcons.VolumeHigh,
                "mute" to EonaIcons.VolumeMute,
                "navigation" to EonaIcons.Navigation,
                "mapPin" to EonaIcons.MapPin,
                "flag" to EonaIcons.Flag,
                "gps" to EonaIcons.Gps,
                "radar" to EonaIcons.Radar,
                "camera" to EonaIcons.Camera,
                "shield" to EonaIcons.Shield,
                "warning" to EonaIcons.Warning,
                "cone" to EonaIcons.Construction,
                "toll" to EonaIcons.Toll,
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
                        EonaIcon(icon, contentDescription = name, tint = colors.textSecondary)
                        EonaText(name, style = EonaTheme.typography.caption, color = colors.textTertiary)
                    }
                }
            }
        }

        ShowcaseSection("Séparateur") {
            EonaDivider()
        }
    }
}

@Preview(name = "Composants · dark", showBackground = true, backgroundColor = 0xFF06070A)
@Composable
private fun ComponentGalleryDarkPreview() {
    EonaTheme(darkTheme = true) { ComponentGallery() }
}
