package com.eona.app.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.Account
import com.eona.app.core.model.Role as AccountRole
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.admin.AdminAccount
import com.eona.app.data.admin.AdminApiException
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.subscription.EonaPlusGroup
import com.eona.app.feature.subscription.EonaPlusLabel
import com.eona.app.feature.subscription.EonaPlusStyle
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Un refus ferme en même temps le contenu de toutes les pages de la gestion (iOS AdminAccessState). */
class AdminAccess {
    var denied by mutableStateOf<String?>(null)

    fun permits(account: Account?, token: String?): Boolean =
        denied == null && token != null && account?.role == AccountRole.Admin && !account.isRestricted

    /** Message à montrer, ou null pour une annulation. Session refusée : accès fermé, compte relu. */
    suspend fun receive(error: Throwable): String? {
        if (error is CancellationException) return null
        if (error is AdminApiException) {
            if (error.closesAccess) {
                denied = error.text
                AccountRepository.reload()
            }
            return error.text
        }
        return "Connexion impossible. Réessaie."
    }
}

/** Page de gestion : fond OLED, barre transparente, contenu défilant. */
@Composable
fun AdminPage(
    title: String,
    onBack: () -> Unit,
    allowed: Boolean,
    denied: String?,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = EonaTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .background(EonaPlusStyle.canvas),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Retour" },
                contentAlignment = Alignment.Center,
            ) { EonaIcon(EonaIcons.ChevronLeft, contentDescription = null, tint = EonaPlusStyle.mineral, size = 22.dp) }
            EonaText(title, style = EonaTheme.typography.headline, color = EonaPlusStyle.primary, modifier = Modifier.align(Alignment.Center))
            if (action != null && allowed) Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(spacing.lg),
        ) {
            Column(Modifier.widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(spacing.xxl)) {
                if (allowed) {
                    content()
                } else {
                    AdminNotice("Accès administrateur", denied ?: "Session administrateur requise.")
                }
                Spacer(Modifier.height(spacing.lg))
            }
        }
    }
}

@Composable
fun AdminNotice(title: String, message: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md)) {
        EonaText(title, style = EonaTheme.typography.headline, color = EonaPlusStyle.primary)
        EonaText(message, style = EonaTheme.typography.subhead, color = EonaPlusStyle.secondary)
        if (retry != null) AdminLink("Réessayer", onClick = retry)
    }
}

@Composable
fun AdminLoading() {
    Row(
        Modifier.fillMaxWidth().padding(EonaTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = EonaPlusStyle.lavender, strokeWidth = 2.dp)
        EonaText("Chargement…", style = EonaTheme.typography.subhead, color = EonaPlusStyle.secondary)
    }
}

/** Lien d'action lavande, cible 44 dp. */
@Composable
fun AdminLink(text: String, enabled: Boolean = true, color: Color = EonaPlusStyle.lavender, onClick: () -> Unit) {
    EonaText(
        text,
        style = EonaTheme.typography.bodyStrong,
        color = if (enabled) color else EonaPlusStyle.muted,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
fun AdminSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md)) {
        EonaPlusLabel(title, Modifier.padding(start = EonaTheme.spacing.lg))
        EonaPlusGroup(content = content)
    }
}

@Composable
fun AdminValueRow(title: String, value: String, color: Color = EonaPlusStyle.primary) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(EonaTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        EonaText(title, style = EonaTheme.typography.subhead, color = EonaPlusStyle.secondary, modifier = Modifier.weight(1f))
        EonaText(value, style = EonaTheme.typography.bodyStrong, color = color, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun AdminNavigationRow(title: String, subtitle: String, icon: ImageVector, color: Color = EonaPlusStyle.lavender, onClick: () -> Unit) {
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { EonaIcon(icon, contentDescription = null, tint = color, size = 20.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            EonaText(title, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary)
            EonaText(subtitle, style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
        }
        EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = EonaPlusStyle.muted, size = 16.dp)
    }
}

/** Champ de saisie sur surface sombre. */
@Composable
fun AdminField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.None,
    singleLine: Boolean = true,
) {
    Box(Modifier.fillMaxWidth().padding(EonaTheme.spacing.lg)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 3,
            textStyle = EonaTheme.typography.body.merge(TextStyle(color = EonaPlusStyle.primary)),
            cursorBrush = SolidColor(EonaPlusStyle.lavender),
            keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = keyboardType, autoCorrectEnabled = false),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) EonaText(placeholder, style = EonaTheme.typography.body, color = EonaPlusStyle.muted)
                inner()
            },
        )
    }
}

/** Choix en pastilles : rôle, filtre. */
@Composable
fun AdminPills(options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
        options.forEach { (value, label) ->
            val on = value == selected
            EonaText(
                label,
                style = EonaTheme.typography.subhead,
                color = if (on) EonaPlusStyle.canvas else if (enabled) EonaPlusStyle.primary else EonaPlusStyle.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) EonaPlusStyle.lavender else EonaPlusStyle.surface)
                    .border(0.5.dp, EonaPlusStyle.line, RoundedCornerShape(50))
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = EonaTheme.spacing.md, vertical = EonaTheme.spacing.sm),
            )
        }
    }
}

object AdminFormat {
    private val counts = NumberFormat.getIntegerInstance(Locale.FRANCE)
    private val currency = NumberFormat.getCurrencyInstance(Locale.FRANCE)
    private val dates = DateTimeFormatter.ofPattern("d MMM yyyy 'à' HH:mm", Locale.FRENCH)

    fun count(value: Int?): String = value?.let { counts.format(it.toLong()) } ?: "—"
    fun euros(value: Double?): String = value?.let { currency.format(it) } ?: "—"
    fun milliseconds(value: Double?): String = value?.let { "${Math.round(it)} ms" } ?: "—"

    fun date(iso: String?): String {
        val instant = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return "—"
        return dates.format(instant.atZone(ZoneId.systemDefault()))
    }

    fun date(millis: Long): String = dates.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun role(role: String): String = when (role) {
        "guest" -> "Invité"
        "client" -> "Client"
        "admin" -> "Admin"
        else -> role
    }

    fun access(account: AdminAccount): String = when {
        account.banned -> "Banni"
        account.suspended == true || account.revoked == true -> "Suspendu"
        account.role == "admin" -> "Admin"
        account.hasPlus == true -> if (account.access == "trial") "Essai EONA+" else "EONA+"
        else -> "Gratuit"
    }

    fun activity(account: AdminAccount): String = when {
        account.inTrip == true -> "En trajet"
        account.online == true -> "Connecté"
        else -> "Hors ligne"
    }
}
