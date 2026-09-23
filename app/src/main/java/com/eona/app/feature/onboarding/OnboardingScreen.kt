package com.eona.app.feature.onboarding

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
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.AuthOutcome
import com.eona.app.data.account.GoogleAuth
import com.eona.app.data.account.GoogleResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import com.eona.app.R
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.launch

private enum class Mode { Choose, Guest, Login, Register, Forgot, Reset }

@Composable
fun OnboardingRoute() {
    OnboardingScreen()
}

@Composable
fun OnboardingScreen() {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var mode by remember { mutableStateOf(Mode.Choose) }
    var pseudo by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var referral by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun launch(block: suspend () -> String?) {
        error = null; info = null; loading = true
        scope.launch { val e = block(); loading = false; if (e != null) error = e }
    }

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

    // Google's sheet, then the server checks the token: the app decides nothing.
    fun google() {
        error = null
        loading = true
        scope.launch {
            when (val result = GoogleAuth.identityToken(context)) {
                // An empty message means the driver simply closed Google's sheet.
                is GoogleResult.Failure -> if (result.message.isNotEmpty()) error = result.message
                is GoogleResult.Token -> {
                    val outcome = AccountRepository.signInWithGoogle(result.idToken)
                    if (outcome is AuthOutcome.Failure) error = outcome.message
                }
            }
            loading = false
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
            EonaText("EONA", style = EonaTheme.typography.displayHero, color = colors.accent)
            val subtitle = when (mode) {
                Mode.Choose -> null
                Mode.Guest -> "Choisis un pseudo et un mot de passe."
                Mode.Login -> "Content de te revoir."
                Mode.Register -> "7 jours d'essai gratuit — ou un code de parrainage."
                Mode.Forgot -> "Reçois un code par email."
                Mode.Reset -> "Entre le code reçu et ton nouveau mot de passe."
            }
            subtitle?.let {
                EonaText(
                    it,
                    style = EonaTheme.typography.subhead,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Column(Modifier.padding(top = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                when (mode) {
                    Mode.Choose -> {
                        Primary("Créer un compte", loading = false) { mode = Mode.Register }
                        Ghost("Se connecter") { mode = Mode.Login; error = null }
                        Ghost("Continuer en invité") { mode = Mode.Guest; error = null }
                        if (GoogleAuth.isAvailable) GoogleButton("Continuer avec Google", ::google)
                    }
                    Mode.Guest -> {
                        Field(pseudo, { pseudo = it.trim() }, "Pseudo", KeyboardCapitalization.None)
                        Field(password, { password = it }, "Mot de passe (8 min.)", KeyboardCapitalization.None, KeyboardType.Password, password = true)
                        EonaText(
                            "Il sert à retrouver ton compte si tu réinstalles l'app. Un compte invité est supprimé au bout de 7 jours.",
                            style = EonaTheme.typography.footnote,
                            color = colors.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Primary("Continuer", loading) { submit { AccountRepository.claimGuest(pseudo, password) } }
                        Back { mode = Mode.Choose; error = null }
                    }
                    Mode.Login -> {
                        Field(email, { email = it.trim() }, "Email ou pseudo", KeyboardCapitalization.None, KeyboardType.Email)
                        Field(password, { password = it }, "Mot de passe", KeyboardCapitalization.None, KeyboardType.Password, password = true)
                        Primary("Se connecter", loading) { submit { AccountRepository.login(email, password) } }
                        if (GoogleAuth.isAvailable) GoogleButton("Se connecter avec Google", ::google)
                        Back(label = "Mot de passe oublié ?") { mode = Mode.Forgot; error = null; info = null }
                        Back { mode = Mode.Choose; error = null }
                    }
                    Mode.Register -> {
                        Field(pseudo, { pseudo = it.trim() }, "Pseudo", KeyboardCapitalization.None)
                        Field(email, { email = it.trim() }, "Email", KeyboardCapitalization.None, KeyboardType.Email)
                        Field(password, { password = it }, "Mot de passe (8 min.)", KeyboardCapitalization.None, KeyboardType.Password, password = true)
                        // Only here, at creation: a code turns the new account into 6 months of membership.
                        Field(referral, { referral = it.uppercase().trim() }, "Code de parrainage (facultatif)", KeyboardCapitalization.Characters, KeyboardType.Text)
                        Primary("Créer le compte", loading) {
                            submit { AccountRepository.register(email, password, pseudo, referral.ifBlank { null }) }
                        }
                        if (GoogleAuth.isAvailable) GoogleButton("Créer un compte avec Google", ::google)
                        Back { mode = Mode.Choose; error = null }
                    }
                    Mode.Forgot -> {
                        Field(email, { email = it.trim() }, "Email", KeyboardCapitalization.None, KeyboardType.Email)
                        Primary("Envoyer le code", loading) {
                            launch { AccountRepository.forgot(email); info = "Si un compte existe, un code a été envoyé."; mode = Mode.Reset; null }
                        }
                        Back { mode = Mode.Login; error = null; info = null }
                    }
                    Mode.Reset -> {
                        Field(code, { code = it.trim() }, "Code reçu par email", KeyboardCapitalization.None, KeyboardType.Number)
                        Field(password, { password = it }, "Nouveau mot de passe (8 min.)", KeyboardCapitalization.None, KeyboardType.Password, password = true)
                        Primary("Réinitialiser", loading) {
                            launch {
                                val e = AccountRepository.resetPassword(email, code, password)
                                if (e == null) { info = "Mot de passe changé, connecte-toi."; password = ""; code = ""; mode = Mode.Login }
                                e
                            }
                        }
                        Back { mode = Mode.Login; error = null; info = null }
                    }
                }
                error?.let {
                    EonaText(it, style = EonaTheme.typography.footnote, color = colors.hazard, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                info?.let {
                    EonaText(it, style = EonaTheme.typography.footnote, color = colors.accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, EonaTheme.shapes.md)
            .border(1.dp, colors.border, EonaTheme.shapes.md)
            .padding(horizontal = EonaTheme.spacing.md, vertical = EonaTheme.spacing.md),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = EonaTheme.typography.body.merge(TextStyle(color = colors.textPrimary)),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = keyboardType),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    EonaText(placeholder, style = EonaTheme.typography.body, color = colors.textTertiary)
                }
                inner()
            },
        )
    }
}

@Composable
private fun Primary(label: String, loading: Boolean, onClick: () -> Unit) {
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accent, EonaTheme.shapes.lg)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = EonaTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        EonaText(if (loading) "…" else label, style = EonaTheme.typography.bodyStrong, color = colors.onAccent)
    }
}

@Composable
private fun Ghost(label: String, onClick: () -> Unit) {
    val colors = EonaTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, EonaTheme.shapes.lg)
            .clickable(onClick = onClick)
            .padding(vertical = EonaTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        EonaText(label, style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
    }
}

/** Google's mark and the words, on a plain surface as Google asks. */
@Composable
internal fun GoogleButton(label: String, onClick: () -> Unit) {
    val colors = EonaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, EonaTheme.shapes.md)
            .border(1.dp, colors.border, EonaTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(vertical = EonaTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painter = painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(18.dp))
        EonaText(label, style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
    }
}

@Composable
private fun Back(label: String = "Retour", onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = EonaTheme.spacing.sm), contentAlignment = Alignment.Center) {
        EonaText(label, style = EonaTheme.typography.subhead, color = EonaTheme.colors.textSecondary)
    }
}
