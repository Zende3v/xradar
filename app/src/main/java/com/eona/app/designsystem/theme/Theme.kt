package com.eona.app.designsystem.theme

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
import com.eona.app.designsystem.foundation.LocalEonaShapes
import com.eona.app.designsystem.foundation.LocalEonaSpacing
import com.eona.app.designsystem.foundation.EonaShapes
import com.eona.app.designsystem.foundation.EonaSpacing

val LocalEonaColors = staticCompositionLocalOf<EonaColors> {
    error("EonaColors not provided — wrap your content in EonaTheme { }.")
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
fun EonaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) EonaDarkColors else EonaLightColors

    CompositionLocalProvider(
        LocalEonaColors provides colors,
        LocalEonaTypography provides EonaDefaultTypography,
        LocalEonaSpacing provides EonaSpacing(),
        LocalEonaShapes provides EonaShapes(),
        LocalEonaElevation provides EonaElevation(),
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
 * `EonaTheme.colors.accent`, `EonaTheme.typography.body`, etc.
 */
object EonaTheme {
    val colors: EonaColors
        @Composable @ReadOnlyComposable get() = LocalEonaColors.current
    val typography: EonaTypography
        @Composable @ReadOnlyComposable get() = LocalEonaTypography.current
    val spacing: EonaSpacing
        @Composable @ReadOnlyComposable get() = LocalEonaSpacing.current
    val shapes: EonaShapes
        @Composable @ReadOnlyComposable get() = LocalEonaShapes.current
    val elevation: EonaElevation
        @Composable @ReadOnlyComposable get() = LocalEonaElevation.current
}

/** Maps our semantic tokens onto a Material 3 [ColorScheme] used only as substrate. */
private fun EonaColors.toMaterialColorScheme(): ColorScheme {
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
