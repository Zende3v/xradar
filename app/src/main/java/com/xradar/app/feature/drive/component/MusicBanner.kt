package com.xradar.app.feature.drive.component

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * Compact mini-player under the HUD's search bar: what plays in any player (Spotify, Apple
 * Music, Deezer, local files...) with previous / play-pause / next. With nothing playing the player is still there,
 * and play starts the last music player. Every control is at least 48 dp, for a thumb
 * while driving.
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
            is MediaPlaybackState.Active -> Player(
                title = state.title ?: state.appLabel ?: "Lecture en cours",
                subtitle = state.artist ?: state.appLabel.orEmpty(),
                art = state.art,
                isPlaying = state.isPlaying,
                onAction = onAction,
            )
            is MediaPlaybackState.Idle -> Player(
                title = "Appuie sur lecture",
                subtitle = idleSubtitle(state.installedApps),
                art = null,
                isPlaying = false,
                onAction = onAction,
            )
            MediaPlaybackState.PermissionMissing -> AccessMissing(onAction)
        }
    }
}

/** The apps play can wake up: the installed ones, or the three supported when none is. */
private fun idleSubtitle(installed: List<MusicApp>): String =
    if (installed.isEmpty()) "Spotify, Apple Music ou Deezer" else installed.joinToString(" · ") { it.label }

/** Art, title and subtitle on one line each, then the three transport controls. */
@Composable
private fun Player(
    title: String,
    subtitle: String,
    art: Bitmap?,
    isPlaying: Boolean,
    onAction: (MusicAction) -> Unit,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Row(
        modifier = Modifier.padding(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Artwork(art)
        Column(modifier = Modifier.weight(1f)) {
            XRadarText(
                title,
                style = XRadarTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            XRadarText(
                subtitle,
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
                    if (isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play,
                ),
                contentDescription = if (isPlaying) "Pause" else "Lecture",
                onClick = { onAction(MusicAction.PlayPause) },
                tint = colors.textPrimary,
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

/** Square album art, or a music note on an empty square when there is none. */
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

@Preview(name = "Mini-player · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380)
@Composable
private fun MusicBannerPreview() {
    XRadarTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicBanner(
                state = MediaPlaybackState.Active("Spotify", "Titre très long d'un morceau", "Artiste", null, true),
                onAction = {},
            )
            MusicBanner(state = MediaPlaybackState.Idle(listOf(MusicApp.Spotify, MusicApp.Deezer)), onAction = {})
            MusicBanner(state = MediaPlaybackState.PermissionMissing, onAction = {})
        }
    }
}
