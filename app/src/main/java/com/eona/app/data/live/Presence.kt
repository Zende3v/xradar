package com.eona.app.data.live

import com.eona.app.core.model.GeoPoint
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.routing.ActiveTripRepository
import com.eona.app.location.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Presence as the app sends it: a ping while the app is on screen or a trip runs, nothing else
 * in the background. A position goes only during a trip, and once when the app is left outside
 * a trip (`closing`): where it was last used. Both follow the privacy switches ([LiveApi]).
 */
object Presence {
    private val api = LiveApi()
    // Not the screen's scope: the last ping must leave even as the screen goes away.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var onScreen = false

    fun shown() {
        onScreen = true
    }

    /** The app leaves the screen: outside a trip, it says so once, with where it was. */
    fun left() {
        onScreen = false
        if (ActiveTripRepository.destination.value == null) scope.launch { send(closing = true) }
    }

    /** One ping of the presence loop: sent on screen or during a trip, nothing otherwise. */
    suspend fun ping() {
        if (onScreen || ActiveTripRepository.destination.value != null) send(closing = false)
    }

    private suspend fun send(closing: Boolean) {
        val privacy = AppPreferences.settings.value
        val token = AccountRepository.token?.takeIf { privacy.presence || privacy.usageTime } ?: return
        val inTrip = ActiveTripRepository.destination.value != null
        val fix = LocationRepository.location.value.takeIf { privacy.presence && (inTrip || closing) }
        api.presence(
            token,
            inTrip = inTrip,
            position = fix?.let { GeoPoint(it.latitude, it.longitude) },
            speedKmh = fix?.speedKmh?.roundToInt()?.coerceAtLeast(0),
            countTime = privacy.usageTime,
            closing = closing,
        )
    }
}
