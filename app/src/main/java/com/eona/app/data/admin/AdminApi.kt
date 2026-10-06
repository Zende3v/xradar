package com.eona.app.data.admin

import com.eona.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Gestion EONA (iOS AdminAPI) : session administrateur obligatoire, aucun repli sur l'appareil. */
class AdminApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun overview(token: String): AdminOverview = call("GET", "/api/admin/overview", token = token) { AdminOverview.parse(it) }

    suspend fun trips(offset: Int = 0, token: String): AdminTripsPage =
        call("GET", "/api/admin/trips?limit=40&offset=$offset", token = token) { AdminTripsPage.parse(it) }

    suspend fun accounts(query: String = "", role: String? = null, banned: Boolean? = null, offset: Int = 0, token: String): AdminAccountsPage {
        val params = buildList {
            add("limit=50")
            add("offset=$offset")
            if (query.isNotEmpty()) add("q=" + java.net.URLEncoder.encode(query, "UTF-8"))
            if (role != null) add("role=$role")
            if (banned != null) add("banned=$banned")
        }
        return call("GET", "/api/admin/accounts?" + params.joinToString("&"), token = token) { AdminAccountsPage.parse(it) }
    }

    suspend fun account(id: String, token: String): AdminAccount =
        call("GET", path(id), token = token) { AdminAccount.parse(it.getJSONObject("account")) }

    suspend fun action(id: String, action: String, reason: String? = null, token: String): AdminActionResult {
        val body = JSONObject().put("action", action)
        if (!reason.isNullOrEmpty()) body.put("reason", reason)
        return call("POST", path(id) + "/action", body, token) { o ->
            AdminActionResult(
                account = o.optJSONObject("account")?.let(AdminAccount::parse),
                revokedSessions = o.intOrNull("revokedSessions"),
                unbanned = if (o.has("unbanned") && !o.isNull("unbanned")) o.optBoolean("unbanned") else null,
            )
        }
    }

    suspend fun bans(token: String): AdminBansPage = call("GET", "/api/admin/accounts/bans", token = token) { AdminBansPage.parse(it) }

    suspend fun update(id: String, role: String, displayName: String, token: String): AdminAccount =
        call("PATCH", path(id), JSONObject().put("role", role).put("displayName", displayName), token) { AdminAccount.parse(it.getJSONObject("account")) }

    suspend fun create(username: String, email: String, password: String, role: String, token: String): AdminAccount {
        val body = JSONObject().put("username", username).put("email", email).put("password", password).put("role", role)
        return call("POST", "/api/admin/accounts", body, token) { AdminAccount.parse(it.getJSONObject("account")) }
    }

    private fun path(id: String) = "/api/admin/accounts/" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private suspend fun <T> call(method: String, path: String, body: JSONObject? = null, token: String, parse: (JSONObject) -> T): T =
        withContext(Dispatchers.IO) {
            if (token.isEmpty()) throw AdminApiException(AdminApiException.Kind.Unauthorized)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody(JSON))
                .build()
            val (code, text) = try {
                client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: java.io.IOException) {
                throw AdminApiException(AdminApiException.Kind.Offline)
            }
            when {
                code == 401 -> throw AdminApiException(AdminApiException.Kind.Unauthorized)
                code == 403 -> throw AdminApiException(AdminApiException.Kind.Forbidden)
                code >= 500 -> throw AdminApiException(AdminApiException.Kind.Unavailable)
                code !in 200..299 -> {
                    val error = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw AdminApiException(AdminApiException.Kind.Refused, error)
                }
            }
            try {
                parse(JSONObject(text))
            } catch (e: Exception) {
                throw AdminApiException(AdminApiException.Kind.InvalidResponse)
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Refus de la gestion, dit en clair. [Kind.Unauthorized] et [Kind.Forbidden] ferment toutes les pages. */
class AdminApiException(val kind: Kind, private val error: String = "") : Exception(error) {
    enum class Kind { Unauthorized, Forbidden, Unavailable, InvalidResponse, Refused, Offline }

    val closesAccess: Boolean get() = kind == Kind.Unauthorized || kind == Kind.Forbidden

    val text: String
        get() = when (kind) {
            Kind.Unauthorized -> "Session expirée. Reconnecte-toi."
            Kind.Forbidden -> "Accès administrateur retiré."
            Kind.Unavailable -> "Service indisponible. Réessaie."
            Kind.InvalidResponse -> "Réponse serveur indisponible. Réessaie."
            Kind.Offline -> "Connexion impossible. Réessaie."
            Kind.Refused -> when (error) {
                "cannot demote or ban yourself", "cannot moderate yourself", "cannot demote, ban or suspend yourself", "cannot ban your own identity" ->
                    "Impossible de modifier tes propres droits ici."
                "cannot disable last admin", "cannot delete last admin" -> "Dernier admin actif : modification interdite."
                "member identity required" -> "Email, pseudo et authentification requis avant changement de rôle."
                "not found" -> "Compte introuvable."
                "email already registered" -> "Email déjà utilisé."
                "username taken" -> "Pseudo déjà utilisé."
                "invalid role" -> "Rôle invalide."
                else -> "Action refusée. Vérifie les champs et réessaie."
            }
        }
}

data class AdminOverview(
    val generatedAt: String,
    val presence: AdminPresence,
    val accounts: AdminAccountCounts,
    val trips: AdminTripCounts,
    val routing: AdminRouting,
    val routingStats: AdminRoutingStats,
    val here: AdminHereUsage,
) {
    companion object {
        fun parse(o: JSONObject) = AdminOverview(
            generatedAt = o.optString("generatedAt"),
            presence = o.getJSONObject("presence").let { p ->
                AdminPresence(
                    ttlSeconds = p.optInt("ttlSeconds", 90),
                    groups = p.optJSONArray("groups").objects().map { g ->
                        AdminPresenceGroup(g.optString("id"), g.optString("label"), g.optInt("total"), g.optInt("online"), g.optInt("offline"), g.optInt("inTrip"))
                    },
                )
            },
            accounts = o.getJSONObject("accounts").let { AdminAccountCounts(it.optInt("total"), it.optInt("banned"), it.optInt("suspended")) },
            trips = o.getJSONObject("trips").let { AdminTripCounts(it.optInt("retainedCount"), it.optInt("totalRecorded")) },
            routing = o.getJSONObject("routing").let { r ->
                val v = r.optJSONObject("valhalla") ?: JSONObject()
                AdminRouting(
                    provider = r.stringOrNull("provider"),
                    valhallaState = v.optString("state", ""),
                    version = v.stringOrNull("version"),
                    mapVersion = v.stringOrNull("mapVersion"),
                    fallbacks = r.optJSONObject("fallbacks")?.intOrNull("total"),
                    fallbacksSince = r.optJSONObject("fallbacks")?.stringOrNull("since"),
                )
            },
            routingStats = o.getJSONObject("routingStats").let { s ->
                AdminRoutingStats(
                    available = s.optBoolean("available"),
                    retentionDays = s.optInt("retentionDays"),
                    totalRequests = s.intOrNull("totalRequests"),
                    cacheHits = s.intOrNull("cacheHits"),
                    weekRequests = s.intOrNull("weekRequests"),
                    medianMs = s.doubleOrNull("medianMs"),
                    p95Ms = s.doubleOrNull("p95Ms"),
                )
            },
            here = o.getJSONObject("here").let { h ->
                val history = h.optJSONObject("history") ?: JSONObject()
                AdminHereUsage(
                    monthUsed = h.optInt("monthUsed"),
                    dailyCap = h.intOrNull("dailyCap"),
                    monthlyBudgetEUR = h.doubleOrNull("monthlyBudgetEUR"),
                    estimatedMonthEUR = h.doubleOrNull("estimatedMonthEUR"),
                    blocked = h.stringOrNull("blocked"),
                    since = history.stringOrNull("since"),
                    totalRequests = history.optInt("totalRequests"),
                    totalEstimatedEUR = history.doubleOrNull("totalEstimatedEUR"),
                    weekRequests = history.optInt("weekRequests"),
                    weekEstimatedEUR = history.doubleOrNull("weekEstimatedEUR"),
                    weekComplete = history.optBoolean("weekComplete", true),
                    earlierHistoryComplete = history.optBoolean("earlierHistoryComplete", true),
                )
            },
        )
    }
}

data class AdminPresence(val ttlSeconds: Int, val groups: List<AdminPresenceGroup>)
data class AdminPresenceGroup(val id: String, val label: String, val total: Int, val online: Int, val offline: Int, val inTrip: Int)
data class AdminAccountCounts(val total: Int, val banned: Int, val suspended: Int)
data class AdminTripCounts(val retainedCount: Int, val totalRecorded: Int)
data class AdminRouting(
    val provider: String?,
    val valhallaState: String,
    val version: String?,
    val mapVersion: String?,
    val fallbacks: Int?,
    val fallbacksSince: String?,
)
data class AdminRoutingStats(
    val available: Boolean,
    val retentionDays: Int,
    val totalRequests: Int?,
    val cacheHits: Int?,
    val weekRequests: Int?,
    val medianMs: Double?,
    val p95Ms: Double?,
)
data class AdminHereUsage(
    val monthUsed: Int,
    val dailyCap: Int?,
    val monthlyBudgetEUR: Double?,
    val estimatedMonthEUR: Double?,
    val blocked: String?,
    val since: String?,
    val totalRequests: Int,
    val totalEstimatedEUR: Double?,
    val weekRequests: Int,
    val weekEstimatedEUR: Double?,
    val weekComplete: Boolean,
    val earlierHistoryComplete: Boolean,
)

data class AdminTripsPage(val total: Int, val next: Int?, val retentionPerAccount: Int, val trips: List<AdminTrip>) {
    companion object {
        fun parse(o: JSONObject) = AdminTripsPage(
            total = o.optInt("total"),
            next = o.intOrNull("next"),
            retentionPerAccount = o.optInt("retentionPerAccount"),
            trips = o.optJSONArray("trips").objects().map { t ->
                AdminTrip(
                    id = t.optString("id"),
                    startedAtMillis = t.optDouble("startedAt").toLong(),
                    distanceMeters = t.optInt("distanceMeters"),
                    durationSeconds = t.optInt("durationSeconds"),
                    arrived = if (t.has("arrived") && !t.isNull("arrived")) t.optBoolean("arrived") else null,
                    engines = t.optJSONArray("engines").strings(),
                    platform = t.stringOrNull("platform"),
                )
            },
        )
    }
}

/** Flux anonyme : aucun nom, identifiant de compte ni lieu. */
data class AdminTrip(
    val id: String,
    val startedAtMillis: Long,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val arrived: Boolean?,
    val engines: List<String>,
    val platform: String?,
)

data class AdminAccountsPage(val total: Int, val next: Int?, val accounts: List<AdminAccount>) {
    companion object {
        fun parse(o: JSONObject) = AdminAccountsPage(
            total = o.optInt("total"),
            next = o.intOrNull("next"),
            accounts = o.optJSONArray("accounts").objects().map(AdminAccount::parse),
        )
    }
}

data class AdminAccount(
    val id: String,
    val username: String?,
    val displayName: String?,
    val email: String?,
    val role: String,
    val access: String?,
    val hasPlus: Boolean?,
    val banned: Boolean,
    val banId: String?,
    val banReason: String?,
    val bannedAt: String?,
    val suspended: Boolean?,
    val revoked: Boolean?,
    val createdAt: String?,
    val lastSeenAt: String?,
    val platform: String?,
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val tripCount: Int?,
    val online: Boolean?,
    val inTrip: Boolean?,
    val devices: List<String>,
) {
    val name: String get() = listOf(displayName, username).firstOrNull { !it.isNullOrBlank() } ?: "Compte"

    companion object {
        fun parse(o: JSONObject): AdminAccount {
            val known = (o.optJSONArray("knownDeviceIds") ?: o.optJSONArray("deviceIds")).strings().toMutableList()
            o.stringOrNull("deviceId")?.let { if (it !in known) known += it }
            val app = o.optJSONObject("app")
            return AdminAccount(
                id = o.optString("id"),
                username = o.stringOrNull("username"),
                displayName = o.stringOrNull("displayName"),
                email = o.stringOrNull("email"),
                role = o.optString("role", "guest"),
                access = o.stringOrNull("access"),
                hasPlus = o.boolOrNull("hasPlus"),
                banned = o.optBoolean("banned"),
                banId = o.stringOrNull("banId"),
                banReason = o.stringOrNull("banReason"),
                bannedAt = o.stringOrNull("bannedAt"),
                suspended = o.boolOrNull("suspended"),
                revoked = o.boolOrNull("revoked"),
                createdAt = o.stringOrNull("createdAt"),
                lastSeenAt = o.stringOrNull("lastSeenAt"),
                platform = o.stringOrNull("platform"),
                model = app?.stringOrNull("model"),
                osVersion = app?.stringOrNull("osVersion"),
                appVersion = app?.stringOrNull("appVersion"),
                tripCount = o.optJSONObject("stats")?.intOrNull("tripCount"),
                online = o.boolOrNull("online"),
                inTrip = o.boolOrNull("inTrip"),
                devices = known.filter { it.isNotEmpty() },
            )
        }
    }
}

data class AdminActionResult(val account: AdminAccount?, val revokedSessions: Int?, val unbanned: Boolean?)

data class AdminBansPage(val total: Int, val bans: List<AdminBanRecord>) {
    companion object {
        fun parse(o: JSONObject) = AdminBansPage(
            total = o.optInt("total"),
            bans = o.optJSONArray("bans").objects().map { b ->
                AdminBanRecord(
                    accountId = b.optString("accountId"),
                    accountExists = b.optBoolean("accountExists"),
                    displayName = b.stringOrNull("displayName"),
                    email = b.stringOrNull("email"),
                    bannedAt = b.stringOrNull("bannedAt"),
                    reason = b.stringOrNull("reason"),
                    deviceCount = b.optInt("deviceCount"),
                )
            },
        )
    }
}

data class AdminBanRecord(
    val accountId: String,
    val accountExists: Boolean,
    val displayName: String?,
    val email: String?,
    val bannedAt: String?,
    val reason: String?,
    val deviceCount: Int,
)

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
private fun JSONObject.doubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
private fun JSONObject.boolOrNull(key: String): Boolean? = if (!has(key) || isNull(key)) null else optBoolean(key)
