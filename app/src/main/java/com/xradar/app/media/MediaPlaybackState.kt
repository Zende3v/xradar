package com.xradar.app.media

import android.graphics.Bitmap

/** A music app whose playback XRadar can show and control. */
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

    /** Access is granted but none of the apps has a session; [installedApps] are the ones play can wake up. */
    data class Idle(val installedApps: List<MusicApp>) : MediaPlaybackState

    /** A session of one of the apps, playing or paused. */
    data class Active(
        val app: MusicApp,
        val title: String?,
        val artist: String?,
        /** Album art, already scaled down for the banner; null when the app gives none. */
        val art: Bitmap?,
        val isPlaying: Boolean,
    ) : MediaPlaybackState
}
