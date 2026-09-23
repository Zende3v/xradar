package com.eona.app.feature.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.ReferralCode
import com.eona.app.data.account.ReferralSettings
import com.eona.app.data.account.isoMillis
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Parrainage (admin only): mint codes worth months of membership, choose how long a new code stays
 * usable, and act on the ones already out — extend, revoke, replace. A code is only accepted when
 * an account is created.
 */
@Composable
fun ReferralRoute(onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var codes by remember { mutableStateOf<List<ReferralCode>>(emptyList()) }
    var settings by remember { mutableStateOf<ReferralSettings?>(null) }
    // What the slider shows while it is dragged; saved when it is let go.
    var months by remember { mutableFloatStateOf(3f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<ReferralCode?>(null) }

    LaunchedEffect(Unit) {
        codes = AccountRepository.referrals()
        AccountRepository.referralSettings()?.let {
            settings = it
            months = it.validityMonths.toFloat()
        }
    }

    fun replace(updated: ReferralCode) {
        codes = if (codes.any { it.code == updated.code }) codes.map { if (it.code == updated.code) updated else it } else listOf(updated) + codes
    }

    opened?.let { code ->
        ReferralDetail(
            initial = code,
            onChange = ::replace,
            onBack = { opened = null },
        )
        return
    }

    EonaScreenScaffold(title = "Parrainage", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaListGroup(title = "Durée de validité") {
                    val range = settings
                    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                        if (range == null) {
                            EonaText("Chargement…", style = EonaTheme.typography.body, color = colors.textTertiary)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EonaText("Un code reste utilisable", style = EonaTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                                EonaText(duration(months.roundToInt()), style = EonaTheme.typography.bodyStrong, color = colors.accent)
                            }
                            Slider(
                                value = months,
                                onValueChange = { months = it },
                                onValueChangeFinished = {
                                    val chosen = months.roundToInt()
                                    if (chosen != range.validityMonths) {
                                        scope.launch {
                                            if (AccountRepository.setReferralValidity(chosen)) {
                                                settings = range.copy(validityMonths = chosen)
                                                message = "Les prochains codes dureront ${duration(chosen)}."
                                            } else {
                                                message = "Changement impossible — réseau ou droits admin."
                                            }
                                        }
                                    }
                                },
                                valueRange = range.minMonths.toFloat()..range.maxMonths.toFloat(),
                                steps = (range.maxMonths - range.minMonths - 1).coerceAtLeast(0),
                                colors = sliderColors(),
                            )
                            Row {
                                EonaText(duration(range.minMonths), style = EonaTheme.typography.footnote, color = colors.textTertiary, modifier = Modifier.weight(1f))
                                EonaText(duration(range.maxMonths), style = EonaTheme.typography.footnote, color = colors.textTertiary)
                            }
                        }
                    }
                }
                EonaText(
                    "Elle s'applique aux codes créés ensuite. Les codes déjà distribués gardent leur date de fin : seul « Prolonger » la déplace.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = spacing.md),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaButton(
                    text = "Générer un code",
                    loading = busy,
                    fillWidth = true,
                    onClick = {
                        busy = true
                        scope.launch {
                            val created = AccountRepository.createReferral()
                            if (created != null) {
                                clipboard.setText(AnnotatedString(created.code))
                                message = "Code ${created.code} créé et copié."
                                codes = listOf(created) + codes
                            } else {
                                message = "Création impossible — réseau ou droits admin."
                            }
                            busy = false
                        }
                    },
                )
                message?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.accent) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaListGroup(title = "Mes codes") {
                    if (codes.isEmpty()) {
                        EonaListRow(title = "Aucun code pour l'instant")
                    } else {
                        codes.forEachIndexed { i, c ->
                            EonaListRow(
                                title = c.code,
                                subtitle = detail(c),
                                onClick = { opened = c },
                                trailing = {
                                    EonaText(
                                        if (c.redemptions == 1) "1 usage" else "${c.redemptions} usages",
                                        style = EonaTheme.typography.callout,
                                        color = if (c.redemptions > 0) colors.success else colors.textTertiary,
                                    )
                                },
                            )
                            if (i < codes.lastIndex) EonaDivider(Modifier.padding(start = spacing.lg))
                        }
                    }
                }
                EonaText(
                    "Un code se saisit uniquement à la création du compte. Ouvre un code pour le copier, le prolonger, le révoquer ou le remplacer.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = spacing.md),
                )
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** One code in full: what it grants, when it ends, what was done to it, and what can still be. */
@Composable
private fun ReferralDetail(initial: ReferralCode, onChange: (ReferralCode) -> Unit, onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf(initial) }
    var extra by remember { mutableFloatStateOf(3f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    fun act(action: String, months: Int? = null) {
        busy = true
        scope.launch {
            val updated = AccountRepository.actOnReferral(code.code, action, months)
            if (updated != null) {
                onChange(updated)
                if (action == "regenerate") {
                    clipboard.setText(AnnotatedString(updated.code))
                    message = "Nouveau code ${updated.code}, copié."
                    code = updated
                } else {
                    code = updated
                    message = if (action == "revoke") "Code révoqué." else "Code prolongé."
                }
            } else {
                message = "Action impossible — réseau ou droits admin."
            }
            busy = false
        }
    }

    EonaScreenScaffold(title = code.code, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            EonaListGroup {
                Figure("Code", code.code)
                Figure("Accorde", "${code.months} mois de membre")
                Figure("Créé le", shortDate(code.createdAt))
                code.expiresAt?.let {
                    Figure("Expire le", day(it))
                    Figure("Temps restant", code.remainingLabel())
                }
                Figure("Utilisations", code.redemptions.toString())
                Figure("État", if (code.revokedAt != null) "Révoqué" else if (code.active) "Actif" else "Expiré")
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EonaText("Prolonger de", style = EonaTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    EonaText(duration(extra.roundToInt()), style = EonaTheme.typography.bodyStrong, color = colors.accent)
                }
                Slider(value = extra, onValueChange = { extra = it }, valueRange = 1f..12f, steps = 10, colors = sliderColors())
                EonaButton(text = "Prolonger", loading = busy, fillWidth = true, onClick = { act("extend", extra.roundToInt()) })
                EonaButton(
                    text = "Copier le code",
                    variant = EonaButtonVariant.Secondary,
                    fillWidth = true,
                    onClick = {
                        clipboard.setText(AnnotatedString(code.code))
                        message = "Copié."
                    },
                )
                EonaButton(text = "Régénérer", variant = EonaButtonVariant.Secondary, fillWidth = true, onClick = { act("regenerate") })
                EonaButton(text = "Révoquer", variant = EonaButtonVariant.Secondary, fillWidth = true, onClick = { act("revoke") })
                message?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.accent) }
                EonaText(
                    "Régénérer révoque ce code et en crée un nouveau à sa place. Tout est consigné ci-dessous.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                )
            }

            if (code.history.isNotEmpty()) {
                EonaListGroup(title = "Historique") {
                    code.history.forEachIndexed { i, event ->
                        EonaListRow(
                            title = event.label,
                            subtitle = listOfNotNull(event.by.ifBlank { null }, event.at?.let(::day)).joinToString(" · "),
                        )
                        if (i < code.history.lastIndex) EonaDivider(Modifier.padding(start = spacing.lg))
                    }
                }
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun Figure(title: String, value: String) {
    EonaListRow(
        title = title,
        trailing = { EonaText(value, style = EonaTheme.typography.callout, color = EonaTheme.colors.textSecondary) },
    )
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = EonaTheme.colors.accent,
    activeTrackColor = EonaTheme.colors.accent,
    inactiveTrackColor = EonaTheme.colors.surfaceHigh,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

/** "3 mois de membre · expire le 12/03/2027 · dans 5 mois", or "révoqué". */
private fun detail(code: ReferralCode): String {
    val parts = mutableListOf("${code.months} mois de membre")
    when {
        code.revokedAt != null -> parts += "révoqué"
        code.expiresAt != null -> parts += "expire le ${day(code.expiresAt)} · ${code.remainingLabel()}"
    }
    return parts.joinToString(" · ")
}

/** "1 mois", "3 mois", "1 an". */
private fun duration(months: Int): String = if (months >= 12) "1 an" else "$months mois"

/** "12/03/2027"; "—" when unreadable. */
private fun day(iso: String): String =
    isoMillis(iso)?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it)) } ?: "—"
