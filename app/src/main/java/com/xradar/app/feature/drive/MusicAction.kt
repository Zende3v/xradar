package com.xradar.app.feature.drive

import com.xradar.app.media.MusicApp

/** What the driver can do with the HUD's music button and banner. */
sealed interface MusicAction {
    /** The music button: open the banner, or close it. */
    data object ToggleBanner : MusicAction

    data object PlayPause : MusicAction

    data object Next : MusicAction

    data object Previous : MusicAction

    /** Android's notification access settings, to grant the permission. */
    data object OpenAccessSettings : MusicAction

    /** Open one of the music apps (nothing plays). */
    data class Launch(val app: MusicApp) : MusicAction
}
