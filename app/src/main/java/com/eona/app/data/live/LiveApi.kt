package com.eona.app.data.live

import com.eona.app.BuildConfig
import com.eona.app.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Presence (`/api/live`): the app says it is open, and whether a trip runs. What goes with it
 * follows the privacy switches — a position only with "Présence et position", the time spent
 * only with "Temps d'utilisation". The app never sends what it was not allowed to.
 */
class LiveApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun presence(
        token: String,
        inTrip: Boolean,
        position: GeoPoint? = null,
        speedKmh: Int? = null,
        countTime: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val json = JSONObject().put("inTrip", inTrip)
        if (countTime) json.put("session", true)
        if (position != null) {
            json.put("lat", position.lat).put("lon", position.lon)
            speedKmh?.let { json.put("speedKmh", it) }
        }
        val body = json.toString().toRequestBody(JSON)
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/api/live/presence")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
