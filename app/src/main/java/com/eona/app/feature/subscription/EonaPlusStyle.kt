package com.eona.app.feature.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/** Pastels EONA+ : référence Telegram Premium choisie par Arthur. Fond OLED, quel que soit le thème (iOS EonaPlusStyle). */
object EonaPlusStyle {
    val canvas = Color(0xFF0A0A0C)
    val surface = Color(0xFF1C1C1E)
    val primary = Color(0xFFF5F5F7)
    val secondary = Color(0xFFA4A4AC)
    val muted = Color(0xFF85858D)
    val mineral = Color(0xFFB0B5BB)
    val sky = Color(0xFF9FCAF1)
    val lavender = Color(0xFFBDB2EE)
    val pink = Color(0xFFE6AED3)
    val peach = Color(0xFFF1C0A7)
    val mint = Color(0xFFACD9C9)
    val spectrum = listOf(sky, lavender, pink, peach, mint)
    val line = Color.White.copy(alpha = 0.08f)
    val groupShape = RoundedCornerShape(26.dp)
}

/** Micro-libellé majuscule, lettres espacées. */
@Composable
fun EonaPlusLabel(text: String, modifier: Modifier = Modifier, color: Color = EonaPlusStyle.secondary) {
    EonaText(
        text.uppercase(),
        style = EonaTheme.typography.caption.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
        color = color,
        modifier = modifier,
    )
}

@Composable
fun EonaPlusDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(EonaPlusStyle.line))
}

/** Une surface commune aux lignes, sans carte répétée pour chaque fonction. */
@Composable
fun EonaPlusGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EonaPlusStyle.groupShape)
            .background(EonaPlusStyle.surface)
            .border(0.5.dp, EonaPlusStyle.line, EonaPlusStyle.groupShape),
        content = content,
    )
}

/** Action pastel fixe, indépendante de la couleur choisie dans les réglages. */
@Composable
fun EonaPlusAction(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val spacing = EonaTheme.spacing
    val capsule = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(capsule)
            .background(Brush.horizontalGradient(listOf(EonaPlusStyle.sky, EonaPlusStyle.lavender, EonaPlusStyle.pink)))
            .border(0.5.dp, Color.White.copy(alpha = 0.25f), capsule)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.xl, vertical = spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EonaText(title, style = EonaTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = EonaPlusStyle.canvas)
        if (icon != null) {
            EonaIcon(icon, contentDescription = null, tint = EonaPlusStyle.canvas, size = 15.dp)
        } else {
            // Flèche vers la droite : la flèche retour retournée.
            EonaIcon(
                EonaIcons.ArrowLeft,
                contentDescription = null,
                tint = EonaPlusStyle.canvas,
                size = 15.dp,
                modifier = Modifier.graphicsLayer { scaleX = -1f },
            )
        }
    }
}
