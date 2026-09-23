package com.eona.app.feature.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eona.app.R
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.launch

/** The terms as the app carries them: the version that must be agreed to, the summary, the text. */
object Terms {
    /** Bump this when a change needs the driver to agree again — not for a typo. */
    const val VERSION = "1.1"
    const val URL = "https://cgu.zylo-app.fr"

    val summary = listOf(
        "EONA aide à la conduite : elle ne remplace ni la signalisation, ni ta vigilance. Tu restes seul responsable au volant.",
        "L'application est réservée aux personnes d'au moins 17 ans.",
        "Les signalements des conducteurs et les données des partenaires peuvent être inexacts ou en retard.",
        "Tes signalements servent à tout le monde ; l'éditeur ne vend aucune donnée.",
        "Partager ton trajet ou rouler en groupe est facultatif : ta position ne part que si tu l'acceptes, et se coupe quand tu veux. Faire suivre quelqu'un à son insu est interdit.",
        "Un compte peut être suspendu en cas d'abus : faux signalements, usage détourné, contenu illicite.",
        "Le détail des données, de leur durée et de tes droits est dans la politique de confidentialité.",
    )

    /** The text shipped with the app, one block per line ("## " marks a heading). */
    fun paragraphs(context: Context): List<String> = runCatching {
        context.resources.openRawResource(R.raw.cgu).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }.getOrDefault(listOf("Les conditions ne sont pas disponibles hors connexion. Ouvre cgu.zylo-app.fr."))
}

/**
 * The terms of use, and the act of accepting them. Shown at first launch, and again only when a
 * new version has to be agreed to. A summary comes first, the whole text is one tap away, and the
 * box is empty until the driver ticks it themselves.
 */
@Composable
fun TermsScreen() {
    var declined by remember { mutableStateOf(AppPreferences.settings.value.termsDeclined) }
    var fullText by remember { mutableStateOf(false) }
    when {
        fullText -> TermsTextScreen(onClose = { fullText = false })
        declined -> DeclinedView(onRead = { fullText = true }, onBack = { declined = false })
        else -> AcceptView(onRead = { fullText = true }, onDecline = {
            AppPreferences.declineTerms()
            declined = true
        })
    }
}

@Composable
private fun AcceptView(onRead: () -> Unit, onDecline: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    var ticked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            EonaText("Conditions générales d'utilisation", style = EonaTheme.typography.title, color = colors.textPrimary)
            EonaText(
                "Version ${Terms.VERSION} · à lire avant d'utiliser EONA",
                style = EonaTheme.typography.footnote,
                color = colors.textTertiary,
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            EonaText("En bref", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
            Terms.summary.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                    EonaText(line, style = EonaTheme.typography.body, color = colors.textSecondary)
                }
            }
            EonaText(
                "Le résumé ne remplace pas le texte : seules les conditions complètes engagent.",
                style = EonaTheme.typography.footnote,
                color = colors.textTertiary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(EonaTheme.shapes.md)
                .background(colors.surface)
                .clickable(onClick = onRead)
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EonaText(
                "Lire les conditions complètes",
                style = EonaTheme.typography.bodyStrong,
                color = colors.accent,
                modifier = Modifier.weight(1f),
            )
            EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, size = 18.dp)
        }

        // The box starts empty: only the driver ticks it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { ticked = !ticked },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TickBox(ticked)
            EonaText(
                "J'ai lu et j'accepte les Conditions générales d'utilisation",
                style = EonaTheme.typography.body,
                color = colors.textPrimary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            EonaButton(
                text = "Accepter",
                onClick = {
                    AppPreferences.acceptTerms(Terms.VERSION)
                    // The account keeps the proof too: which version, and when.
                    scope.launch { AccountRepository.recordTerms(Terms.VERSION) }
                },
                enabled = ticked,
                fillWidth = true,
                modifier = Modifier.alpha(if (ticked) 1f else 0.5f),
            )
            EonaButton(text = "Refuser", onClick = onDecline, variant = EonaButtonVariant.Secondary, fillWidth = true)
        }
    }
}

@Composable
private fun TickBox(ticked: Boolean) {
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(EonaTheme.shapes.xs)
            .background(if (ticked) colors.accent else Color.Transparent)
            .border(2.dp, if (ticked) colors.accent else colors.textSecondary, EonaTheme.shapes.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (ticked) EonaIcon(EonaIcons.Check, contentDescription = null, tint = colors.onAccent, size = 16.dp)
    }
}

@Composable
private fun DeclinedView(onRead: () -> Unit, onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Spacer(Modifier.weight(1f))
        EonaIcon(EonaIcons.Info, contentDescription = null, tint = colors.textSecondary, size = 40.dp)
        EonaText(
            "EONA ne peut pas démarrer",
            style = EonaTheme.typography.title,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        EonaText(
            "La navigation, les alertes et le compte reposent sur ces conditions. Sans accord, elles restent inactives. " +
                "Tu peux relire le texte et revenir dessus quand tu veux.",
            style = EonaTheme.typography.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            EonaButton(text = "Relire les conditions", onClick = onRead, fillWidth = true)
            EonaButton(text = "Revenir à l'acceptation", onClick = onBack, variant = EonaButtonVariant.Secondary, fillWidth = true)
        }
    }
}

/** The whole text, as shipped with the app so it reads without a connection. */
@Composable
fun TermsTextScreen(onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val paragraphs = remember { Terms.paragraphs(context) }
    BackHandler(onBack = onClose)
    EonaScreenScaffold(title = "Conditions d'utilisation", onBack = onClose) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                paragraphs.forEach { block ->
                    if (block.startsWith("## ")) {
                        EonaText(
                            block.removePrefix("## "),
                            style = EonaTheme.typography.bodyStrong,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(top = spacing.sm),
                        )
                    } else {
                        EonaText(block, style = EonaTheme.typography.body, color = colors.textSecondary)
                    }
                }
                EonaText(
                    "Voir la version en ligne",
                    style = EonaTheme.typography.footnote,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(top = spacing.md)
                        .clickable { runCatching { uri.openUri(Terms.URL) } },
                )
                Spacer(Modifier.height(spacing.xxl))
            }
        }
    }
}
