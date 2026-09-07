package com.xradar.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.account.AuthOutcome
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlinx.coroutines.launch

private enum class Mode { Choose, Guest, Login, Register }

@Composable
fun OnboardingRoute() {
    OnboardingScreen()
}

@Composable
fun OnboardingScreen() {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(Mode.Choose) }
    var pseudo by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(block: suspend () -> AuthOutcome) {
        error = null
        loading = true
        scope.launch {
            val outcome = block()
            loading = false
            if (outcome is AuthOutcome.Failure) error = outcome.message
            // Success → AccountRepository.account updates → the app gate switches screens.
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .padding(spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            XRadarText("x_radar", style = XRadarTheme.typography.displayHero, color = colors.accent)
            XRadarText(
                when (mode) {
                    Mode.Choose -> "Ta route, tes radars, tes alertes."
                    Mode.Guest -> "Choisis un pseudo unique."
                    Mode.Login -> "Content de te revoir."
                    Mode.Register -> "Crée ton compte membre."
                },
                style = XRadarTheme.typography.subhead,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Column(Modifier.padding(top = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                when (mode) {
                    Mode.Choose -> {
                        Primary("Créer un compte", loading = false) { mode = Mode.Register }
                        Ghost("Se connecter") { mode = Mode.Login; error = null }
                        Ghost("Continuer en invité") { mode = Mode.Guest; error = null }
                    }
                    Mode.Guest -> {
                        Field(pseudo, { pseudo = it.trim() }, "Pseudo", KeyboardCapitalization.None)
                        Primary("Continuer", loading) { submit { AccountRepository.claimGuest(pseudo) } }
                        Back { mode = Mode.Choose; error = null }
                    }
                    Mode.Login -> {
                        Field(email, { email = it.trim() }, "Email", KeyboardCapitalization.None, KeyboardType.Email)
                        Field(password, { password = it }, "Mot de passe", KeyboardCapitalization.None, KeyboardType.Password, password = true)
                        Primary("Se connecter", loading) { submit { AccountRepository.login(email, password) } }
                        Back { mode = Mode.Choose; error = null }
                    }
                    Mode.Register -> {
                        Field(pseudo, { pseudo = it.trim() }, "Pseudo", KeyboardCapitalization.None)
                        Field(email, { email = it.trim() }, "Email", KeyboardCapitalization.None, KeyboardType.Email)
                        Field(password, { password = it }, "Mot de passe (8 min.)", KeyboardCapitalization.None, KeyboardType.Password, password = true)
                        Primary("Créer le compte", loading) { submit { AccountRepository.register(email, password, pseudo) } }
                        Back { mode = Mode.Choose; error = null }
                    }
                }
                error?.let {
                    XRadarText(it, style = XRadarTheme.typography.footnote, color = colors.hazard, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    caps: KeyboardCapitalization,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, XRadarTheme.shapes.md)
            .border(1.dp, colors.border, XRadarTheme.shapes.md)
            .padding(horizontal = XRadarTheme.spacing.md, vertical = XRadarTheme.spacing.md),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = XRadarTheme.typography.body.merge(TextStyle(color = colors.textPrimary)),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = keyboardType),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    XRadarText(placeholder, style = XRadarTheme.typography.body, color = colors.textTertiary)
                }
                inner()
            },
        )
    }
}

@Composable
private fun Primary(label: String, loading: Boolean, onClick: () -> Unit) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accent, XRadarTheme.shapes.lg)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        XRadarText(if (loading) "…" else label, style = XRadarTheme.typography.bodyStrong, color = colors.onAccent)
    }
}

@Composable
private fun Ghost(label: String, onClick: () -> Unit) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, XRadarTheme.shapes.lg)
            .clickable(onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        XRadarText(label, style = XRadarTheme.typography.bodyStrong, color = colors.textPrimary)
    }
}

@Composable
private fun Back(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = XRadarTheme.spacing.sm), contentAlignment = Alignment.Center) {
        XRadarText("Retour", style = XRadarTheme.typography.subhead, color = XRadarTheme.colors.textSecondary)
    }
}
