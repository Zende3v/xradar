package com.xradar.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.xradar.app.core.model.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Framework LocationManager fallback (no Google dependency). */
class AndroidLocationClient(private val context: Context) : LocationClient {

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMs: Long): Flow<LocationSample> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasLocationPermission(context)) {
            close(MissingLocationPermissionException())
            return@callbackFlow
        }
        // All four methods implemented explicitly for correctness on API 26-29
        // (the interface defaults only exist from API 30).
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toSample())
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in API 29, still abstract on 26-28")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        manager.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { manager.removeUpdates(listener) }
    }
}
