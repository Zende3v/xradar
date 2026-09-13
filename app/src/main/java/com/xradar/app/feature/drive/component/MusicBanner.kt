package com.xradar.app.feature.drive.component

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.xradar.app.R
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarIconButton
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.drive.MusicAction
import com.xradar.app.media.MediaPlaybackState
import com.xradar.app.media.MusicApp

/**
 * Compact mini-player under the HUD's search bar: what plays in Spotify, Apple Music or
 * Deezer with previous / play-pause / next, or why nothing can be shown. Every control is
 * at least 48 dp, for a thumb while driving.
 */
@Composable
fun MusicBanner(
    state: MediaPlaybackState,
    onAction: (MusicAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XRadarTheme.colors
    XRadarSurface(
        modifier = modifier.fillMaxWidth(),
        shape = XRadarTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, colors.border),
    ) {
        when (state) {
            is MediaPlaybackState.Active -> NowPlaying(state, onAction)
            is MediaPlaybackState.Idle -> NothingPlaying(state.installedApps, onAction)
            MediaPlaybackState.PermissionMissing -> AccessMissing(onAction)
        }
    }
}

/** Art, title and artist on one line each, then the three transport controls. */
@Composable
private fun NowPlaying(state: MediaPlaybackState.Active, onAction: (MusicAction) -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Row(
        modifier = Modifier.padding(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Artwork(state.art)
        Column(modifier = Modifier.weight(1f)) {
            XRadarText(
                state.title ?: state.app.label,
                style = XRadarTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            XRadarText(
                state.artist ?: state.app.label,
                style = XRadarTheme.typography.footnote,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            XRadarIconButton(
                icon = ImageVector.vectorResource(R.drawable.ic_music_previous),
                contentDescription = "Titre précédent",
                onClick = { onAction(MusicAction.Previous) },
                tint = colors.textPrimary,
                size = CONTROL_SIZE,
            )
            XRadarIconButton(
                icon = ImageVector.vectorResource(
                    if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play,
                ),
                contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                onClick = { onAction(MusicAction.PlayPause) },
                tint = colors.onAccent,
                background = colors.accent,
                size = CONTROL_SIZE,
            )
            XRadarIconButton(
                icon = ImageVector.vectorResource(R.drawable.ic_music_next),
                contentDescription = "Titre suivant",
                onClick = { onAction(MusicAction.Next) },
                tint = colors.textPrimary,
                size = CONTROL_SIZE,
            )
        }
    }
}

/** Square album art, or a music note on an empty square when the app gives none. */
@Composable
private fun Artwork(art: Bitmap?) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .size(ART_SIZE)
            .clip(XRadarTheme.shapes.sm)
            .background(colors.surfaceHigh)
            .border(1.dp, colors.border, XRadarTheme.shapes.sm),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            val image = remember(art) { art.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ART_SIZE),
            )
        } else {
            XRadarIcon(
                ImageVector.vectorResource(R.drawable.ic_music),
                contentDescription = null,
                tint = colors.textTertiary,
                size = 24.dp,
            )
        }
    }
}

/** None of the apps plays: say so, and offer the installed ones. */
@Composable
private fun NothingPlaying(apps: List<MusicApp>, onAction: (MusicAction) -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Column(
        modifier = Modifier.padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            XRadarIcon(
                ImageVector.vectorResource(R.drawable.ic_music),
                contentDescription = null,
                tint = colors.textSecondary,
                size = 20.dp,
            )
            XRadarText("Aucune musique en cours", style = XRadarTheme.typography.subhead, color = colors.textPrimary)
        }
        if (apps.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                apps.forEach { app -> AppChip(app = app, onClick = { onAction(MusicAction.Launch(app)) }) }
            }
        }
    }
}

/** An installed music app, with its own launcher icon; a tap opens it. */
@Composable
private fun AppChip(app: MusicApp, onClick: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(app) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(APP_ICON_PX, APP_ICON_PX).asImageBitmap()
        }.getOrNull()
    }
    Row(
        modifier = Modifier
            .heightIn(min = CONTROL_SIZE)
            .clip(XRadarTheme.shapes.pill)
            .background(colors.surfaceHigh)
            .border(1.dp, colors.border, XRadarTheme.shapes.pill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(XRadarTheme.shapes.sm),
            )
        }
        XRadarText(app.label, style = XRadarTheme.typography.callout, color = colors.textPrimary, maxLines = 1)
    }
}

/** Notification access missing: one line and the way to Android's settings. */
@Composable
private fun AccessMissing(onAction: (MusicAction) -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Row(
        modifier = Modifier.padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        XRadarIcon(
            ImageVector.vectorResource(R.drawable.ic_music),
            contentDescription = null,
            tint = colors.textSecondary,
            size = 24.dp,
        )
        XRadarText(
            "Autoriser l'accès aux médias",
            style = XRadarTheme.typography.subhead,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .heightIn(min = CONTROL_SIZE)
                .clip(XRadarTheme.shapes.pill)
                .background(colors.accent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onAction(MusicAction.OpenAccessSettings) },
                )
                .padding(horizontal = spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            XRadarText(
                "Réglages",
                style = XRadarTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onAccent,
            )
        }
    }
}

/** Touch target of every control, for use while driving. */
private val CONTROL_SIZE = 48.dp
private val ART_SIZE = 48.dp
private const val APP_ICON_PX = 72

@Preview(name = "Mini-player · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380)
@Composable
private fun MusicBannerPreview() {
    XRadarTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicBanner(
                state = MediaPlaybackState.Active(MusicApp.Spotify, "Titre très long d'un morceau", "Artiste", null, true),
                onAction = {},
            )
            MusicBanner(state = MediaPlaybackState.Idle(MusicApp.entries), onAction = {})
            MusicBanner(state = MediaPlaybackState.PermissionMissing, onAction = {})
        }
    }
}
