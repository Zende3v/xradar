package com.eona.app.data.radar

import com.eona.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CheckResult(val name: String, val ok: Boolean, val detail: String)

/** Live connectivity check against the backend, for the in-app diagnostic screen. */
class BackendDiagnostics(val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun run(): List<CheckResult> = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        listOf(
            http("Santé du serveur (/health)", "$base/health"),
            http("Radars (/near, Montpellier)", "$base/api/radars/near?lat=43.6126&lon=3.8767&radius=5000"),
        )
    }

    private fun http(name: String, url: String): CheckResult = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = (response.body?.string() ?: "").take(220)
            CheckResult(name, response.isSuccessful, "HTTP ${response.code}\n$body")
        }
    } catch (e: Exception) {
        CheckResult(name, false, "Échec : ${e.javaClass.simpleName}\n${e.message ?: "(pas de message)"}")
    }
}
