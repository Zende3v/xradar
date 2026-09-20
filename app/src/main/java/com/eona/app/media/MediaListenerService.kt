package com.eona.app.media

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Being an enabled notification listener is what lets [MediaRepository]
 * read and control the media sessions of Spotify, Apple Music and Deezer; EONA never
 * looks at the notifications themselves.
 */
class MediaListenerService : NotificationListenerService()
