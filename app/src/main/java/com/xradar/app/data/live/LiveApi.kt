package com.xradar.app.data.live

import com.xradar.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Presence (`/api/live`): the app says it is open, and whether a trip runs. Counted by the
 * backend, shown to nobody, and no position goes with it.
 */
class LiveApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun presence(token: String, inTrip: Boolean): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("inTrip", inTrip).toString().toRequestBody(JSON)
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
