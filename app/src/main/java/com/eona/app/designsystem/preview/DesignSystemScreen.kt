package com.eona.app.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Living reference for the design-system foundations. Not a product screen —
 * it renders the raw tokens (color, type, spacing, radii) so the direction can
 * be reviewed on-device before components and real screens are built on top.
 */
@Composable
fun DesignSystemScreen() {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing

    ShowcaseScaffold {
        Header()

        ShowcaseSection("Surfaces") {
            SwatchGrid(
                listOf(
                    "canvas" to colors.canvas,
                    "surface" to colors.surface,
                    "surfaceElevated" to colors.surfaceElevated,
                    "surfaceHigh" to colors.surfaceHigh,
                ),
            )
        }

        ShowcaseSection("Texte") {
            SwatchGrid(
                listOf(
                    "textPrimary" to colors.textPrimary,
                    "textSecondary" to colors.textSecondary,
                    "textTertiary" to colors.textTertiary,
                    "textDisabled" to colors.textDisabled,
                ),
            )
        }

        ShowcaseSection("Accent & feedback") {
            SwatchGrid(
                listOf(
                    "accent" to colors.accent,
                    "success" to colors.success,
                    "warning" to colors.warning,
                    "danger" to colors.danger,
                    "info" to colors.info,
                ),
            )
        }

        ShowcaseSection("Domaine — sécurité routière") {
            SwatchGrid(
                listOf(
                    "speedSafe" to colors.speedSafe,
                    "speedOver" to colors.speedOver,
                    "radarFixed" to colors.radarFixed,
                    "radarMobile" to colors.radarMobile,
                    "controlZone" to colors.controlZone,
                    "hazard" to colors.hazard,
                ),
            )
        }

        ShowcaseSection("Typographie") {
            val t = EonaTheme.typography
            TypeSpecimen("displayHero · 88", "128", t.displayHero)
            TypeSpecimen("display · 40", "Conduite fluide", t.display)
            TypeSpecimen("titleLarge · 28", "Conduite fluide", t.titleLarge)
            TypeSpecimen("title · 22", "Conduite fluide", t.title)
            TypeSpecimen("headline · 17", "Radar fixe à 300 m", t.headline)
            TypeSpecimen("body · 17", "Autoroute A7 — trafic dense", t.body)
            TypeSpecimen("callout · 16", "Autoroute A7 — trafic dense", t.callout)
            TypeSpecimen("subhead · 15", "Autoroute A7 — trafic dense", t.subhead)
            TypeSpecimen("footnote · 13", "Dernière mise à jour il y a 2 min", t.footnote)
            TypeSpecimen("caption · 12", "ZONE DE CONTRÔLE", t.caption)
            TypeSpecimen("label · 16", "Démarrer le trajet", t.label)
            TypeSpecimen("numeric · 17 (tabular)", "128 · 12,4 km · 08:42", t.numeric)
        }

        ShowcaseSection("Espacements") {
            listOf(
                "hair" to spacing.hair, "xs" to spacing.xs, "sm" to spacing.sm,
                "md" to spacing.md, "lg" to spacing.lg, "xl" to spacing.xl,
                "xxl" to spacing.xxl, "xxxl" to spacing.xxxl, "huge" to spacing.huge,
                "giant" to spacing.giant,
            ).forEach { (name, value) -> SpacingRow(name, value) }
        }

        ShowcaseSection("Rayons") {
            val s = EonaTheme.shapes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                RadiusChip("sm", s.sm)
                RadiusChip("md", s.md)
                RadiusChip("lg", s.lg)
                RadiusChip("xl", s.xl)
            }
        }
    }
}

@Composable
private fun Header() {
    val colors = EonaTheme.colors
    val typography = EonaTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
        Text("DESIGN SYSTEM", style = typography.caption, color = colors.accent)
        Text("x_radar", style = typography.display, color = colors.textPrimary)
        Text(
            "Fondations visuelles — Phase 1",
            style = typography.callout,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SwatchGrid(items: List<Pair<String, Color>>) {
    val spacing = EonaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                row.forEach { (name, color) ->
                    Swatch(name, color, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(EonaTheme.shapes.md)
                .background(color)
                .border(1.dp, colors.border, EonaTheme.shapes.md),
        )
        Text(name, style = EonaTheme.typography.caption, color = colors.textSecondary)
    }
}

@Composable
private fun TypeSpecimen(name: String, sample: String, style: TextStyle) {
    val colors = EonaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
        Text(
            sample,
            style = style,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(name, style = EonaTheme.typography.caption, color = colors.textTertiary)
    }
}

@Composable
private fun SpacingRow(name: String, value: Dp) {
    val colors = EonaTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        Text(
            name,
            style = EonaTheme.typography.footnote,
            color = colors.textSecondary,
            modifier = Modifier.width(56.dp),
        )
        Box(
            Modifier
                .height(10.dp)
                .width(value)
                .clip(EonaTheme.shapes.pill)
                .background(colors.accent),
        )
        Text(
            "${value.value.toInt()}dp",
            style = EonaTheme.typography.caption,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun RadiusChip(name: String, shape: Shape) {
    val colors = EonaTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.borderStrong, shape),
        )
        Text(name, style = EonaTheme.typography.caption, color = colors.textTertiary)
    }
}

@Preview(name = "Design system · dark", showBackground = true, backgroundColor = 0xFF06070A)
@Composable
private fun DesignSystemScreenDarkPreview() {
    EonaTheme(darkTheme = true) { DesignSystemScreen() }
}

@Preview(name = "Design system · light", showBackground = true, backgroundColor = 0xFFF6F8FB)
@Composable
private fun DesignSystemScreenLightPreview() {
    EonaTheme(darkTheme = false) { DesignSystemScreen() }
}
