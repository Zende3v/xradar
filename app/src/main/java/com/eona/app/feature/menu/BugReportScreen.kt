package com.eona.app.feature.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.bugs.BugApi
import com.eona.app.data.bugs.BugAppDetails
import com.eona.app.data.bugs.BugCategory
import com.eona.app.data.bugs.BugReport
import com.eona.app.data.bugs.BugSendOutcome
import com.eona.app.data.bugs.BugStatus
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaChip
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.launch

private const val MIN_LENGTH = 10
private const val MAX_LENGTH = 1000
/** The backend's page (bugPage). */
private const val PAGE_SIZE = 50

/**
 * "Signaler un bug", like iOS: a category, what happened (required), how to see it again
 * (optional). The account is the author and the app adds its own details: nothing else is asked.
 */
@Composable
fun BugReportRoute(onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val details = remember { BugAppDetails.current() }
    // One client for the screen, not one per request.
    val api = remember { BugApi() }
    var category by remember { mutableStateOf(BugCategory.Other) }
    var description by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    EonaScreenScaffold(title = "Signaler un bug", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Spacer(Modifier.height(spacing.xs))
            Label("Catégorie")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                BugCategory.entries.forEach { c -> EonaChip(label = c.label, selected = c == category, onClick = { category = c }) }
            }
            Label("Que s'est-il passé ?")
            Area(description, "Ce qui ne va pas, en quelques mots") { description = it }
            Label("Comment le reproduire ?")
            Area(steps, "Facultatif : ce que tu faisais juste avant") { steps = it }
            EonaText(
                "Envoyé avec ton compte et ${details.platform} ${details.os} · EONA ${details.version} · ${details.model}.",
                style = EonaTheme.typography.footnote,
                color = colors.textTertiary,
            )
            EonaButton(
                text = if (sending) "Envoi…" else "Envoyer",
                onClick = {
                    sending = true
                    message = null
                    scope.launch {
                        val outcome = api.send(category, description.trim(), steps.trim(), AccountRepository.token)
                        sending = false
                        when (outcome) {
                            BugSendOutcome.Sent -> onBack()
                            BugSendOutcome.TooMany -> message = "Beaucoup de rapports envoyés récemment : réessaie plus tard."
                            BugSendOutcome.Failed -> message = "Envoi impossible pour l'instant. Vérifie ta connexion et réessaie."
                        }
                    }
                },
                enabled = description.trim().length >= MIN_LENGTH && !sending,
                loading = sending,
                fillWidth = true,
            )
            message?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.danger) }
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun Label(text: String) {
    EonaText(text, style = EonaTheme.typography.bodyStrong, color = EonaTheme.colors.textPrimary)
}

/** A few lines of text, [MAX_LENGTH] at most. */
@Composable
private fun Area(value: String, placeholder: String, onChange: (String) -> Unit) {
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .background(colors.surface, EonaTheme.shapes.md)
            .border(1.dp, colors.border, EonaTheme.shapes.md)
            .padding(EonaTheme.spacing.md),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(MAX_LENGTH)) },
            textStyle = EonaTheme.typography.body.merge(TextStyle(color = colors.textPrimary)),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) EonaText(placeholder, style = EonaTheme.typography.body, color = colors.textTertiary)
                inner()
            },
        )
    }
}

/**
 * "Rapports de bugs" (admins), like iOS: the most recent first, by status, a page at a time; a
 * report opens on its details and its status.
 */
@Composable
fun BugListRoute(onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<BugStatus?>(BugStatus.New) }
    var reports by remember { mutableStateOf<List<BugReport>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<BugReport?>(null) }
    val api = remember { BugApi() }

    suspend fun load(reset: Boolean) {
        loading = true
        val page = api.list(filter, if (reset) null else reports.lastOrNull()?.createdAt, AccountRepository.token)
        loading = false
        failed = page == null
        if (page == null) return
        reports = if (reset) page else reports + page
        more = page.size >= PAGE_SIZE
    }
    LaunchedEffect(filter) { load(reset = true) }

    val shown = open
    EonaScreenScaffold(title = shown?.category?.label ?: "Rapports de bugs", onBack = { if (open != null) open = null else onBack() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Spacer(Modifier.height(spacing.xs))
            if (shown != null) {
                BugDetail(shown) { status ->
                    scope.launch {
                        if (api.setStatus(shown.id, status, AccountRepository.token)) {
                            val updated = shown.copy(status = status)
                            reports = reports.map { if (it.id == updated.id) updated else it }
                            open = updated
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    listOf<Pair<String, BugStatus?>>(
                        "Nouveaux" to BugStatus.New, "En cours" to BugStatus.Progress, "Résolus" to BugStatus.Resolved, "Tous" to null,
                    ).forEach { (label, status) -> EonaChip(label = label, selected = filter == status, onClick = { filter = status }) }
                }
                when {
                    failed && reports.isEmpty() -> EonaText("Chargement impossible.", color = colors.textSecondary)
                    !loading && reports.isEmpty() -> EonaText("Aucun rapport.", color = colors.textSecondary)
                }
                if (reports.isNotEmpty()) {
                    EonaListGroup {
                        reports.forEachIndexed { index, report ->
                            if (index > 0) EonaDivider()
                            BugRow(report) { open = report }
                        }
                    }
                }
                if (more) {
                    EonaButton(text = "Plus anciens", onClick = { scope.launch { load(reset = false) } }, enabled = !loading, fillWidth = true)
                }
            }
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun BugRow(report: BugReport, onClick: () -> Unit) {
    val colors = EonaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(EonaTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EonaText(report.category.label, style = EonaTheme.typography.caption, color = colors.accent, modifier = Modifier.weight(1f))
            EonaText(report.status.label, style = EonaTheme.typography.caption, color = colors.textSecondary)
        }
        EonaText(report.description, style = EonaTheme.typography.body, color = colors.textPrimary, maxLines = 2)
        EonaText(
            "${shortDate(report.createdAt)} · ${report.author ?: "Compte supprimé"} · ${report.app.platform} ${report.app.version}",
            style = EonaTheme.typography.footnote,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun BugDetail(report: BugReport, onStatus: (BugStatus) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Label("Description")
    EonaText(report.description, color = colors.textPrimary)
    report.steps?.let {
        Label("Reproduction")
        EonaText(it, color = colors.textPrimary)
    }
    Label("Détails")
    EonaText(
        "Auteur : ${report.author ?: "Compte supprimé"}\nDate : ${shortDate(report.createdAt)}\n" +
            "App : ${report.app.platform} ${report.app.os} · ${report.app.version}\nAppareil : ${report.app.model}",
        color = colors.textSecondary,
    )
    Label("Statut")
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        BugStatus.entries.forEach { s -> EonaChip(label = s.label, selected = s == report.status, onClick = { onStatus(s) }) }
    }
}
