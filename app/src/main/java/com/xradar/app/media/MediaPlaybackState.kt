package com.xradar.app.media

import android.graphics.Bitmap

/** A streaming app play can open when no player runs; any other player is shown as well. */
enum class MusicApp(val packageName: String, val label: String) {
    Spotify("com.spotify.music", "Spotify"),
    AppleMusic("com.apple.android.music", "Apple Music"),
    Deezer("deezer.android.app", "Deezer");

    companion object {
        fun of(packageName: String?): MusicApp? = entries.firstOrNull { it.packageName == packageName }
    }
}

/** What the music banner shows, read from the system media sessions. */
sealed interface MediaPlaybackState {

    /** Notification access is not granted (or was revoked): no session can be read. */
    data object PermissionMissing : MediaPlaybackState

    /** Access is granted but no player has a session; [installedApps] are the ones play can open. */
    data class Idle(val installedApps: List<MusicApp>) : MediaPlaybackState

    /** A player's session (streaming app or local files), playing or paused. */
    data class Active(
        /** The player's name; null when Android does not let XRadar see that app. */
        val appLabel: String?,
        val title: String?,
        val artist: String?,
        /** Album art, already scaled down for the banner; null when the app gives none. */
        val art: Bitmap?,
        val isPlaying: Boolean,
    ) : MediaPlaybackState
}
