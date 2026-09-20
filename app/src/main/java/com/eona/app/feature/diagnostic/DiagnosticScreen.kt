package com.eona.app.feature.diagnostic

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
import com.eona.app.data.radar.BackendDiagnostics
import com.eona.app.data.radar.CheckResult
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.location.LocationRepository
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

    EonaScreenScaffold(title = "Diagnostic backend", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EonaTheme.spacing.lg, vertical = EonaTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.lg),
        ) {
            EonaCard {
                EonaText("Adresse du backend", style = EonaTheme.typography.headline, color = EonaTheme.colors.textPrimary)
                EonaText(
                    diagnostics.baseUrl,
                    style = EonaTheme.typography.footnote,
                    color = EonaTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = EonaTheme.spacing.xs),
                )
            }

            EonaCard {
                EonaText("GPS", style = EonaTheme.typography.headline, color = EonaTheme.colors.textPrimary)
                val fix = location
                val gpsText = if (fix == null) {
                    "Pas encore de position ($signal) — sans fix GPS, l'app ne charge pas les radars."
                } else {
                    "Fix OK ($signal) — ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}"
                }
                EonaText(
                    gpsText,
                    style = EonaTheme.typography.footnote,
                    color = EonaTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = EonaTheme.spacing.xs),
                )
            }

            EonaButton(
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
    val colors = EonaTheme.colors
    EonaCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
        ) {
            EonaIcon(
                if (result.ok) EonaIcons.Check else EonaIcons.Close,
                contentDescription = null,
                tint = if (result.ok) colors.success else colors.danger,
                size = 20.dp,
            )
            EonaText(result.name, style = EonaTheme.typography.headline, color = colors.textPrimary)
        }
        EonaText(
            result.detail,
            style = EonaTheme.typography.footnote,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = EonaTheme.spacing.sm),
        )
    }
}
