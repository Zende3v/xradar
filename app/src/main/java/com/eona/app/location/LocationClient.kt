package com.eona.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.eona.app.core.model.LocationSample
import kotlinx.coroutines.flow.Flow

/** Streams device position. Backed by Fused (Play Services) or the framework provider. */
interface LocationClient {
    fun locationUpdates(intervalMs: Long): Flow<LocationSample>
}

class MissingLocationPermissionException :
    SecurityException("Location permission not granted")

internal fun hasLocationPermission(context: Context): Boolean {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)
}

/** Picks Fused when Google Play Services is available, otherwise the framework provider. */
object LocationClientFactory {
    fun create(context: Context): LocationClient {
        val playServices = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        return if (playServices) FusedLocationClient(context) else AndroidLocationClient(context)
    }
}
