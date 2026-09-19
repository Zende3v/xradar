package com.xradar.app.data.bugs

import android.os.Build
import com.xradar.app.BuildConfig
import com.xradar.app.data.network.FallbackDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** "Signaler un bug": where the problem is (same values as iOS and the backend). */
enum class BugCategory(val wire: String, val label: String) {
    Map("map", "Carte"),
    Navigation("navigation", "Navigation"),
    Alerts("alerts", "Alertes"),
    Account("account", "Compte"),
    Other("other", "Autre"),
}

/** Where a report stands, for the developers: Nouveau → En cours → Résolu. */
enum class BugStatus(val wire: String, val label: String) {
    New("new", "Nouveau"),
    Progress("progress", "En cours"),
    Resolved("resolved", "Résolu"),
}

/** What the app knows of itself, sent with a report (nothing the driver types). */
data class BugAppDetails(val platform: String, val version: String, val os: String, val model: String) {
    companion object {
        fun current() = BugAppDetails("Android", BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}

/** A report as the developers see it; [author] is the pseudo and role, null once deleted. */
data class BugReport(
    val id: String,
    val status: BugStatus,
    val category: BugCategory,
    val description: String,
    val steps: String?,
    val createdAt: String,
    val author: String?,
    val app: BugAppDetails,
)

enum class BugSendOutcome { Sent, TooMany, Failed }

/** Bug reports (`/api/bugs`): anyone sends, admins read and set the status. Like iOS BugAPI. */
class BugApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** The account (from [token]) is the author: nothing else about the driver goes. */
    suspend fun send(category: BugCategory, description: String, steps: String?, token: String?): BugSendOutcome = withContext(Dispatchers.IO) {
        val app = BugAppDetails.current()
        val body = JSONObject()
            .put("category", category.wire)
            .put("description", description)
            .put("app", JSONObject().put("platform", app.platform).put("version", app.version).put("os", app.os).put("model", app.model))
        if (!steps.isNullOrBlank()) body.put("steps", steps)
        runCatching {
            client.newCall(request("/api/bugs", token).post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
                when {
                    r.code == 429 -> BugSendOutcome.TooMany
                    r.isSuccessful -> BugSendOutcome.Sent
                    else -> BugSendOutcome.Failed
                }
            }
        }.getOrDefault(BugSendOutcome.Failed)
    }

    /** The most recent reports ([status] null: all), older than [before]; null when refused or offline. */
    suspend fun list(status: BugStatus?, before: String?, token: String?): List<BugReport>? = withContext(Dispatchers.IO) {
        val query = listOfNotNull(
            status?.let { "status=${it.wire}" },
            before?.let { "before=${URLEncoder.encode(it, "UTF-8")}" },
        ).joinToString("&")
        runCatching {
            client.newCall(request("/api/bugs" + if (query.isEmpty()) "" else "?$query", token).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val reports = JSONObject(r.body?.string() ?: "").optJSONArray("reports") ?: return@use emptyList()
                (0 until reports.length()).mapNotNull { i -> reports.optJSONObject(i)?.let(::parse) }
            }
        }.getOrNull()
    }

    suspend fun setStatus(id: String, status: BugStatus, token: String?): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("status", status.wire).toString().toRequestBody(JSON)
        runCatching {
            client.newCall(request("/api/bugs/${URLEncoder.encode(id, "UTF-8")}", token).patch(body).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun request(path: String, token: String?) = Request.Builder()
        .url("${baseUrl.trimEnd('/')}$path")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun parse(o: JSONObject): BugReport? {
        val status = BugStatus.entries.firstOrNull { it.wire == o.optString("status") } ?: return null
        val id = o.optString("id").ifBlank { return null }
        val author = o.optJSONObject("author")?.let { a ->
            val role = a.optString("role")
            val name = if (a.isNull("username")) "Sans pseudo" else a.optString("username").ifBlank { "Sans pseudo" }
            name + if (role.isBlank()) "" else " · $role"
        }
        val app = o.optJSONObject("app")
        return BugReport(
            id = id,
            status = status,
            category = BugCategory.entries.firstOrNull { it.wire == o.optString("category") } ?: BugCategory.Other,
            description = o.optString("description"),
            steps = o.optString("steps").ifBlank { null }.takeUnless { o.isNull("steps") },
            createdAt = o.optString("createdAt"),
            author = author,
            app = BugAppDetails(
                app?.optString("platform").orEmpty(),
                app?.optString("version").orEmpty(),
                app?.optString("os").orEmpty(),
                app?.optString("model").orEmpty(),
            ),
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
