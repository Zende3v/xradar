package com.eona.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.UsernameAvailability
import com.eona.app.core.model.UsernameRules
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.AuthOutcome
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaSurface
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the typed name stands. */
private enum class NameCheck { Unchanged, Checking, Available, Taken, Reserved, Invalid, Unknown }

/**
 * "Changer de pseudo", like iOS: the new name, checked as it is typed (its form at once, then the
 * backend after a pause — one request per pause), and saved once free. The backend answers with
 * the account, which replaces the cached one: the menu, the profile and every other screen show
 * the new name at once. Place it last in a full-size box.
 */
@Composable
fun UsernameDialog(current: String, onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var name by remember { mutableStateOf(current) }
    var check by remember { mutableStateOf(NameCheck.Unchanged) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(name) {
        error = null
        val wanted = name.trim()
        check = when {
            wanted.isEmpty() || wanted == current -> NameCheck.Unchanged
            !UsernameRules.isWellFormed(wanted) -> NameCheck.Invalid
            else -> {
                check = NameCheck.Checking
                delay(CHECK_PAUSE_MS)
                when (AccountRepository.usernameAvailability(wanted)) {
                    UsernameAvailability.Available -> NameCheck.Available
                    UsernameAvailability.Taken -> NameCheck.Taken
                    UsernameAvailability.Reserved -> NameCheck.Reserved
                    UsernameAvailability.Invalid -> NameCheck.Invalid
                    null -> NameCheck.Unknown
                }
            }
        }
    }

    fun save() {
        if (check != NameCheck.Available || saving) return
        saving = true
        scope.launch {
            when (val outcome = AccountRepository.updateProfile(username = name.trim())) {
                is AuthOutcome.Success -> onClose()
                // Someone may have taken it in between: the backend has the last word.
                is AuthOutcome.Failure -> error = outcome.message
            }
            saving = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        EonaSurface(
            // Taps on the card stay on it.
            modifier = Modifier
                .padding(spacing.xl)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = EonaTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                EonaText("Changer de pseudo", style = EonaTheme.typography.headline, color = colors.textPrimary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, EonaTheme.shapes.md)
                        .border(1.dp, colors.border, EonaTheme.shapes.md)
                        .padding(spacing.md),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = EonaTheme.typography.body.merge(TextStyle(color = colors.textPrimary)),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                statusLine(check)?.let { (text, isGood) ->
                    EonaText(
                        text,
                        style = EonaTheme.typography.footnote,
                        color = when (isGood) {
                            true -> colors.success
                            false -> colors.danger
                            null -> colors.textTertiary
                        },
                    )
                }
                error?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.danger) }
                EonaText(
                    "3 à 20 caractères : lettres sans accent, chiffres, _ et . Un changement par semaine ; " +
                        "ton ancien pseudo reste à toi pendant 30 jours. Pour te connecter, utilise ensuite le nouveau pseudo ou ton email.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaButton(
                        text = "Annuler",
                        onClick = onClose,
                        variant = EonaButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                        fillWidth = true,
                    )
                    EonaButton(
                        text = "Enregistrer",
                        onClick = ::save,
                        enabled = check == NameCheck.Available,
                        loading = saving,
                        modifier = Modifier.weight(1f),
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

/** The line under the field, and whether it is good news (null: neutral). */
private fun statusLine(check: NameCheck): Pair<String, Boolean?>? = when (check) {
    NameCheck.Unchanged -> null
    NameCheck.Checking -> "Vérification…" to null
    NameCheck.Available -> "Disponible" to true
    NameCheck.Taken -> "Ce pseudo est déjà pris." to false
    NameCheck.Reserved -> "Ce pseudo est réservé." to false
    NameCheck.Invalid -> "Pseudo invalide." to false
    NameCheck.Unknown -> "Vérification impossible pour l'instant, réessaie." to false
}

private const val CHECK_PAUSE_MS = 400L
