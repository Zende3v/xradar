package com.xradar.app.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Start/stop entry points for the [LocationService]. */
object LocationServiceController {

    fun start(context: Context) {
        val intent = Intent(context, LocationService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, LocationService::class.java)
            .setAction(LocationService.ACTION_STOP)
        context.startService(intent)
    }
}
