package com.xradar.app.feature.diagnostic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.data.radar.BackendDiagnostics
import com.xradar.app.data.radar.CheckResult
import com.xradar.app.designsystem.component.XRadarButton
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.location.LocationRepository
import kotlinx.coroutines.launch

@Composable
fun DiagnosticRoute(onBack: () -> Unit) {
    DiagnosticScreen(onBack = onBack)
}

@Composable
fun DiagnosticScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val diagnostics = remember { BackendDiagnostics() }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }

    val location by LocationRepository.location.collectAsStateWithLifecycle()
    val signal by LocationRepository.signal.collectAsStateWithLifecycle()

    fun runChecks() {
        scope.launch {
            running = true
            results = diagnostics.run()
            running = false
        }
    }
    LaunchedEffect(Unit) { runChecks() }

    XRadarScreenScaffold(title = "Diagnostic backend", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = XRadarTheme.spacing.lg, vertical = XRadarTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.lg),
        ) {
            XRadarCard {
                XRadarText("Adresse du backend", style = XRadarTheme.typography.headline, color = XRadarTheme.colors.textPrimary)
                XRadarText(
                    diagnostics.baseUrl,
                    style = XRadarTheme.typography.footnote,
                    color = XRadarTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = XRadarTheme.spacing.xs),
                )
            }

            XRadarCard {
                XRadarText("GPS", style = XRadarTheme.typography.headline, color = XRadarTheme.colors.textPrimary)
                val fix = location
                val gpsText = if (fix == null) {
                    "Pas encore de position ($signal) — sans fix GPS, l'app ne charge pas les radars."
                } else {
                    "Fix OK ($signal) — ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}"
                }
                XRadarText(
                    gpsText,
                    style = XRadarTheme.typography.footnote,
                    color = XRadarTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = XRadarTheme.spacing.xs),
                )
            }

            XRadarButton(
                text = if (running) "Test en cours…" else "Relancer le test",
                onClick = { runChecks() },
                loading = running,
                fillWidth = true,
            )

            results.forEach { CheckCard(it) }
        }
    }
}

@Composable
private fun CheckCard(result: CheckResult) {
    val colors = XRadarTheme.colors
    XRadarCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
        ) {
            XRadarIcon(
                if (result.ok) XRadarIcons.Check else XRadarIcons.Close,
                contentDescription = null,
                tint = if (result.ok) colors.success else colors.danger,
                size = 20.dp,
            )
            XRadarText(result.name, style = XRadarTheme.typography.headline, color = colors.textPrimary)
        }
        XRadarText(
            result.detail,
            style = XRadarTheme.typography.footnote,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = XRadarTheme.spacing.sm),
        )
    }
}
