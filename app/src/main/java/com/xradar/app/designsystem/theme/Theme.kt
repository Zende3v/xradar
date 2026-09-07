package com.xradar.app.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.xradar.app.designsystem.foundation.LocalXRadarShapes
import com.xradar.app.designsystem.foundation.LocalXRadarSpacing
import com.xradar.app.designsystem.foundation.XRadarShapes
import com.xradar.app.designsystem.foundation.XRadarSpacing

val LocalXRadarColors = staticCompositionLocalOf<XRadarColors> {
    error("XRadarColors not provided — wrap your content in XRadarTheme { }.")
}

/**
 * Root theme for the app. Provides every design-system token via CompositionLocals
 * and lays a Material 3 substrate underneath (mapped to our tokens) so low-level
 * primitives — ripples, text selection, cursor — pick up the right colors.
 *
 * The app is dark-first; [darkTheme] lets callers force a scheme (previews, the
 * showcase) independent of the system setting.
 */
@Composable
fun XRadarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) XRadarDarkColors else XRadarLightColors

    CompositionLocalProvider(
        LocalXRadarColors provides colors,
        LocalXRadarTypography provides XRadarDefaultTypography,
        LocalXRadarSpacing provides XRadarSpacing(),
        LocalXRadarShapes provides XRadarShapes(),
        LocalXRadarElevation provides XRadarElevation(),
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            content = content,
        )
    }
}

/**
 * Ergonomic accessors, mirroring the `MaterialTheme.colors` pattern:
 * `XRadarTheme.colors.accent`, `XRadarTheme.typography.body`, etc.
 */
object XRadarTheme {
    val colors: XRadarColors
        @Composable @ReadOnlyComposable get() = LocalXRadarColors.current
    val typography: XRadarTypography
        @Composable @ReadOnlyComposable get() = LocalXRadarTypography.current
    val spacing: XRadarSpacing
        @Composable @ReadOnlyComposable get() = LocalXRadarSpacing.current
    val shapes: XRadarShapes
        @Composable @ReadOnlyComposable get() = LocalXRadarShapes.current
    val elevation: XRadarElevation
        @Composable @ReadOnlyComposable get() = LocalXRadarElevation.current
}

/** Maps our semantic tokens onto a Material 3 [ColorScheme] used only as substrate. */
private fun XRadarColors.toMaterialColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        error = danger,
        onError = onAccent,
        outline = borderStrong,
        outlineVariant = border,
        scrim = scrim,
    )
}
