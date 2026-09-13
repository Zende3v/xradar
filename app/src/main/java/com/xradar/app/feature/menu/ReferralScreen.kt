package com.xradar.app.feature.menu

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
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.account.ReferralCode
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlinx.coroutines.launch

/**
 * Parrainage (admin only): mint codes worth 6 months of membership, and see how many
 * accounts used each. A code is only accepted when an account is created.
 */
@Composable
fun ReferralRoute(onBack: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var codes by remember { mutableStateOf<List<ReferralCode>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { codes = AccountRepository.referrals() }

    XRadarScreenScaffold(title = "Parrainage", onBack = onBack) {
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
                    .clip(XRadarTheme.shapes.lg)
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
                XRadarText(
                    "Générer un code (6 mois de membre)",
                    style = XRadarTheme.typography.bodyStrong,
                    color = if (busy) colors.textTertiary else colors.onAccent,
                )
            }
            message?.let { XRadarText(it, style = XRadarTheme.typography.footnote, color = colors.accent) }

            XRadarListGroup(title = "Mes codes") {
                if (codes.isEmpty()) {
                    XRadarListRow(title = "Aucun code pour l'instant")
                } else {
                    codes.forEachIndexed { i, c ->
                        XRadarListRow(
                            title = c.code,
                            subtitle = "Créé le ${shortDate(c.createdAt)} · ${c.months} mois",
                            onClick = {
                                clipboard.setText(AnnotatedString(c.code))
                                message = "${c.code} copié."
                            },
                            trailing = {
                                XRadarText(
                                    if (c.redemptions == 1) "1 utilisation" else "${c.redemptions} utilisations",
                                    style = XRadarTheme.typography.callout,
                                    color = if (c.redemptions > 0) colors.success else colors.textTertiary,
                                )
                            },
                        )
                        if (i < codes.lastIndex) XRadarDivider(Modifier.padding(start = spacing.lg))
                    }
                }
            }
            XRadarText(
                "Un code se saisit uniquement à la création du compte. Touche un code pour le copier.",
                style = XRadarTheme.typography.footnote,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}
