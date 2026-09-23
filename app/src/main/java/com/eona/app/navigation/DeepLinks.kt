package com.eona.app.navigation

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The links EONA opens from outside: `eona://t/<token>` (a trip someone shares) and
 * `eona://g/<token>` (a group trip's watch link), as the backend's web pages hand them over.
 */
object DeepLinks {
    private val _follow = MutableStateFlow<String?>(null)
    /** A shared trip to follow, until its screen is closed. */
    val follow: StateFlow<String?> = _follow.asStateFlow()

    private val _watch = MutableStateFlow<String?>(null)
    /** A group trip to watch, until its screen is closed. */
    val watch: StateFlow<String?> = _watch.asStateFlow()

    /** Reads the link the app was opened with, if it is one of ours. */
    fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "eona") return
        val token = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        when (uri.host) {
            "t" -> _follow.value = token
            "g" -> _watch.value = token
        }
    }

    fun closeFollow() {
        _follow.value = null
    }

    fun closeWatch() {
        _watch.value = null
    }
}
