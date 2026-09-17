package com.xradar.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.xradar.app.core.model.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Fused Location Provider (Play Services): best accuracy + battery. */
class FusedLocationClient(private val context: Context) : LocationClient {

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMs: Long): Flow<LocationSample> = callbackFlow {
        if (!hasLocationPermission(context)) {
            close(MissingLocationPermissionException())
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toSample()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

/** Maps a framework [Location] to the pure [LocationSample] model. */
internal fun Location.toSample(): LocationSample = LocationSample(
    latitude = latitude,
    longitude = longitude,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    accuracyM = if (hasAccuracy()) accuracy else null,
    timeMs = time,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
)
