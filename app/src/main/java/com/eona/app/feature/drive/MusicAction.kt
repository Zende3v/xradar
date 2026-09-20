package com.eona.app.feature.drive

/** What the driver can do with the HUD's music button and banner. */
sealed interface MusicAction {
    /** The music button: open the banner, or close it. */
    data object ToggleBanner : MusicAction

    /** Play or pause; with nothing playing, start the last music player. */
    data object PlayPause : MusicAction

    data object Next : MusicAction

    data object Previous : MusicAction

    /** Android's notification access settings, to grant the permission. */
    data object OpenAccessSettings : MusicAction
}
