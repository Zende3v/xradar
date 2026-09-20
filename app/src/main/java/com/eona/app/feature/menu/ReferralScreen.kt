package com.eona.app.feature.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.ReferralCode
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.launch

/**
 * Parrainage (admin only): mint codes worth 6 months of membership, and see how many
 * accounts used each. A code is only accepted when an account is created.
 */
@Composable
fun ReferralRoute(onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var codes by remember { mutableStateOf<List<ReferralCode>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { codes = AccountRepository.referrals() }

    EonaScreenScaffold(title = "Parrainage", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EonaTheme.shapes.lg)
                    .background(if (busy) colors.surfaceHigh else colors.accent)
                    .clickable(enabled = !busy) {
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
                    }
                    .padding(vertical = spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                EonaText(
                    "Générer un code (6 mois de membre)",
                    style = EonaTheme.typography.bodyStrong,
                    color = if (busy) colors.textTertiary else colors.onAccent,
                )
            }
            message?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.accent) }

            EonaListGroup(title = "Mes codes") {
                if (codes.isEmpty()) {
                    EonaListRow(title = "Aucun code pour l'instant")
                } else {
                    codes.forEachIndexed { i, c ->
                        EonaListRow(
                            title = c.code,
                            subtitle = "Créé le ${shortDate(c.createdAt)} · ${c.months} mois",
                            onClick = {
                                clipboard.setText(AnnotatedString(c.code))
                                message = "${c.code} copié."
                            },
                            trailing = {
                                EonaText(
                                    if (c.redemptions == 1) "1 utilisation" else "${c.redemptions} utilisations",
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
                "Un code se saisit uniquement à la création du compte. Touche un code pour le copier.",
                style = EonaTheme.typography.footnote,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}
