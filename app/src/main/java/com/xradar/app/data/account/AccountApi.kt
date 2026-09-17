package com.xradar.app.data.account

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.Access
import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
import com.xradar.app.core.model.TripRecord
import com.xradar.app.data.network.FallbackDns
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** An account plus its session token (null token when unauthenticated). */
data class AuthResult(val account: Account, val token: String?)

/** Server-side statistics of the signed-in account. */
data class AccountStats(
    val tripCount: Int = 0,
    val distanceMeters: Long = 0,
    val driveSeconds: Long = 0,
    val alertsTraversed: Int = 0,
    val reportsDeclared: Int = 0,
    val reportsConfirmed: Int = 0,
    val trust: Double = 2.5,
    val trips: List<TripRecord> = emptyList(),
)

/** A referral code an admin minted, and how many accounts used it. */
data class ReferralCode(
    val code: String,
    val createdAt: String,
    val months: Int,
    val redemptions: Int,
)

/** Result of a network auth call: success, or a human error message. */
sealed interface AuthOutcome {
    data class Success(val result: AuthResult) : AuthOutcome
    data class Failure(val message: String) : AuthOutcome
}

/** Client for accounts, auth, sessions and profile (`/api/accounts`). */
class AccountApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = "${baseUrl.trimEnd('/')}$path"

    suspend fun usernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/username-available?u=$username")).build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: return@use false
            JSONObject(body).optBoolean("available", false)
        }
    }

    /** Legacy device sign-in (used to restore a session by deviceId). */
    suspend fun authDevice(deviceId: String): AuthResult? = withContext(Dispatchers.IO) {
        post("/api/accounts/auth", JSONObject().put("deviceId", deviceId).put("platform", "android"))
            .let { (ok, body) -> if (ok) parseAuth(body) else null }
    }

    suspend fun claimGuest(deviceId: String, username: String, password: String): AuthOutcome = withContext(Dispatchers.IO) {
        outcome(
            "/api/accounts/guest",
            JSONObject().put("deviceId", deviceId).put("username", username).put("password", password).put("platform", "android"),
        )
    }

    suspend fun register(email: String, password: String, username: String, referralCode: String?): AuthOutcome =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("email", email).put("password", password).put("username", username)
            if (!referralCode.isNullOrBlank()) payload.put("referralCode", referralCode.trim())
            outcome("/api/accounts/register", payload)
        }

    // ---- Statistics ---------------------------------------------------------

    suspend fun stats(token: String): AccountStats? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me/stats")).header("Authorization", "Bearer $token").build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                parseStats(JSONObject(r.body?.string() ?: ""))
            }
        }.getOrNull()
    }

    suspend fun postTrip(token: String, trip: TripRecord): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("id", trip.id)
            .put("startedAt", trip.startedAt)
            .put("fromLabel", trip.fromLabel)
            .put("toLabel", trip.toLabel)
            .put("distanceMeters", trip.distanceMeters)
            .put("durationSeconds", trip.durationSeconds)
            .put("alertsCount", trip.alertsCount)
            .put("topSpeedKmh", trip.topSpeedKmh)
        authedPost(token, "/api/accounts/me/trips", body)
    }

    suspend fun postDrive(token: String, seconds: Int, meters: Int): Boolean = withContext(Dispatchers.IO) {
        authedPost(token, "/api/accounts/me/drive", JSONObject().put("seconds", seconds).put("meters", meters))
    }

    // ---- Referral codes (admins) --------------------------------------------

    suspend fun referrals(token: String): List<ReferralCode> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/referrals")).header("Authorization", "Bearer $token").build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("referrals") ?: return@use emptyList()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseReferral) }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun createReferral(token: String): ReferralCode? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/referrals"))
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON))
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: "").optJSONObject("referral")?.let(::parseReferral)
            }
        }.getOrNull()
    }

    private fun authedPost(token: String, path: String, body: JSONObject): Boolean = runCatching {
        val req = Request.Builder().url(url(path))
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun parseStats(o: JSONObject): AccountStats {
        val t = o.optJSONObject("totals") ?: JSONObject()
        val trips = o.optJSONArray("trips") ?: JSONArray()
        return AccountStats(
            tripCount = t.optInt("tripCount"),
            distanceMeters = t.optLong("distanceMeters"),
            driveSeconds = t.optLong("driveDurationSeconds"),
            alertsTraversed = t.optInt("alertsTraversed"),
            reportsDeclared = t.optInt("reportsDeclared"),
            reportsConfirmed = t.optInt("reportsConfirmed"),
            trust = o.optDouble("trust", 2.5),
            trips = (0 until trips.length()).mapNotNull { i ->
                val x = trips.optJSONObject(i) ?: return@mapNotNull null
                TripRecord(
                    id = x.optString("id"),
                    startedAt = x.optLong("startedAt"),
                    fromLabel = x.optString("fromLabel"),
                    toLabel = x.optString("toLabel"),
                    distanceMeters = x.optInt("distanceMeters"),
                    durationSeconds = x.optInt("durationSeconds"),
                    alertsCount = x.optInt("alertsCount"),
                    topSpeedKmh = x.optInt("topSpeedKmh"),
                )
            },
        )
    }

    private fun parseReferral(o: JSONObject) = ReferralCode(
        code = o.optString("code"),
        createdAt = o.optString("createdAt"),
        months = o.optInt("months", 6),
        redemptions = o.optInt("redemptions"),
    )

    /** [identifier]: an email (member) or a username (guest); [deviceId] attaches the account to this phone. */
    suspend fun login(identifier: String, password: String, deviceId: String?): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("identifier", identifier).put("password", password)
        if (!deviceId.isNullOrBlank()) payload.put("deviceId", deviceId)
        outcome("/api/accounts/login", payload)
    }

    /**
     * The account behind a session token; null when the token is no longer valid. A server or
     * tunnel error throws, like a lost connection: the session must not be dropped over it.
     */
    suspend fun me(token: String): Account? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me")).header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) return@use null
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body?.string() ?: throw java.io.IOException("empty answer")
            JSONObject(body).optJSONObject("account")?.let(::parseAccount)
        }
    }

    suspend fun updateProfile(token: String, username: String?, avatarUrl: String?): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            if (username != null) put("username", username)
            if (avatarUrl != null) put("avatarUrl", avatarUrl)
        }
        val req = Request.Builder().url(url("/api/accounts/me"))
            .header("Authorization", "Bearer $token")
            .patch(payload.toString().toRequestBody(JSON))
            .build()
        runOutcome(req)
    }

    suspend fun uploadAvatar(token: String, dataUrl: String): AuthOutcome = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/avatar"))
            .header("Authorization", "Bearer $token")
            .post(JSONObject().put("dataUrl", dataUrl).toString().toRequestBody(JSON))
            .build()
        runOutcome(req)
    }

    private fun outcome(path: String, payload: JSONObject): AuthOutcome {
        val req = Request.Builder().url(url(path)).post(payload.toString().toRequestBody(JSON)).build()
        return runOutcome(req)
    }

    private fun runOutcome(req: Request): AuthOutcome = try {
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (r.isSuccessful) {
                parseAuth(body)?.let { AuthOutcome.Success(it) } ?: AuthOutcome.Failure("Réponse invalide")
            } else {
                AuthOutcome.Failure(errorMessage(body))
            }
        }
    } catch (e: Exception) {
        AuthOutcome.Failure("Réseau indisponible")
    }

    private fun post(path: String, payload: JSONObject): Pair<Boolean, String> = try {
        val req = Request.Builder().url(url(path)).post(payload.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r -> r.isSuccessful to (r.body?.string() ?: "") }
    } catch (e: Exception) {
        false to ""
    }

    private fun parseAuth(body: String): AuthResult? {
        if (body.isBlank()) return null
        val o = JSONObject(body)
        val account = o.optJSONObject("account")?.let(::parseAccount) ?: return null
        return AuthResult(account, o.optString("token").ifBlank { null })
    }

    private fun parseAccount(o: JSONObject): Account = Account(
        id = o.optString("id"),
        role = Role.fromWire(o.optString("role")),
        username = o.optString("username").ifBlank { null }.takeUnless { o.isNull("username") },
        displayName = o.optString("displayName").ifBlank { null }.takeUnless { o.isNull("displayName") },
        avatarUrl = o.optString("avatarUrl").ifBlank { null }.takeUnless { o.isNull("avatarUrl") },
        email = o.optString("email").ifBlank { null }.takeUnless { o.isNull("email") },
        banned = o.optBoolean("banned"),
        emailVerified = o.optBoolean("emailVerified"),
        access = Access.fromWire(o.optString("access")),
        canNavigate = if (o.has("canNavigate")) o.optBoolean("canNavigate") else true,
        accessEndsAt = o.optString("accessEndsAt").ifBlank { null }.takeUnless { o.isNull("accessEndsAt") },
        trust = o.optDouble("trust", 2.5),
    )

    /** POST returning {ok}/{error}: null on success, else a friendly message. */
    private fun postSimple(path: String, payload: JSONObject): String? = try {
        val req = Request.Builder().url(url(path)).post(payload.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else friendly(errorRaw(r.body?.string()))
        }
    } catch (e: Exception) {
        "Réseau indisponible"
    }

    private fun errorRaw(body: String?): String =
        runCatching { JSONObject(body ?: "").optString("error").ifBlank { "Erreur" } }.getOrDefault("Erreur")

    suspend fun forgot(email: String): Unit = withContext(Dispatchers.IO) {
        postSimple("/api/accounts/forgot", JSONObject().put("email", email)); Unit
    }

    suspend fun resetPassword(email: String, code: String, password: String): String? = withContext(Dispatchers.IO) {
        postSimple("/api/accounts/reset", JSONObject().put("email", email).put("code", code).put("password", password))
    }

    suspend fun verify(email: String, code: String): String? = withContext(Dispatchers.IO) {
        postSimple("/api/accounts/verify", JSONObject().put("email", email).put("code", code))
    }

    suspend fun resendVerify(email: String): Unit = withContext(Dispatchers.IO) {
        postSimple("/api/accounts/resend-verify", JSONObject().put("email", email)); Unit
    }

    private fun errorMessage(body: String): String = runCatching {
        JSONObject(body).optString("error").ifBlank { "Erreur" }
    }.getOrDefault("Erreur").let(::friendly)

    private fun friendly(err: String): String = when {
        err.contains("taken") -> "Ce pseudo est déjà pris."
        err.contains("invalid username") -> "Pseudo invalide (3–20 caractères : lettres, chiffres, _ .)."
        err.contains("email already") -> "Cet email est déjà utilisé."
        err.contains("invalid email") -> "Email invalide."
        err.contains("password too short") -> "Mot de passe trop court (8 caractères min)."
        err.contains("invalid credentials") -> "Identifiant ou mot de passe incorrect."
        err.contains("banned") -> "Ce compte est banni."
        err.contains("invalid referral") -> "Code de parrainage invalide."
        err.contains("subscription required") -> "Abonnement requis."
        else -> err.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
