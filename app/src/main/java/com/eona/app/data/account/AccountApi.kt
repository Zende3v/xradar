package com.eona.app.data.account

import com.eona.app.BuildConfig
import com.eona.app.core.model.Access
import com.eona.app.core.model.Account
import com.eona.app.core.model.DailyLimits
import com.eona.app.core.model.Role
import com.eona.app.core.model.TripRecord
import com.eona.app.core.model.UsernameAvailability
import com.eona.app.core.model.alertTypeFromWire
import com.eona.app.core.model.wireName
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
    /** What the code grants once used, in months of client access. */
    val months: Int,
    val redemptions: Int,
    /** Usable right now: not revoked, not past its date. */
    val active: Boolean = true,
    /** How long it was given when minted, and when it stops working (ISO-8601). */
    val validityMonths: Int? = null,
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    /** What happened to it: created, extended, revoked, regenerated. */
    val history: List<ReferralEvent> = emptyList(),
) {
    /** "dans 3 mois", "dans 12 jours", "expiré" — what an admin reads at a glance. */
    fun remainingLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val end = isoMillis(expiresAt) ?: return "sans date de fin"
        val seconds = (end - nowMillis) / 1000
        if (seconds <= 0) return "expiré"
        val days = (seconds / 86_400).toInt()
        return when {
            days >= 60 -> "dans ${days / 30} mois"
            days >= 1 -> "dans $days jour" + if (days > 1) "s" else ""
            else -> "aujourd'hui"
        }
    }
}

/** One line in a code's history: what was done, by whom, when. */
data class ReferralEvent(val action: String, val by: String, val at: String?) {
    /** "prolongé", "révoqué"… as an admin says it. */
    val label: String
        get() = when (action) {
            "created" -> "créé"
            "extended" -> "prolongé"
            "revoked" -> "révoqué"
            "regenerated" -> "régénéré"
            "replaces" -> "remplace un code"
            else -> action
        }
}

/** The duration new codes get, and the range an admin may choose from. */
data class ReferralSettings(val validityMonths: Int, val minMonths: Int, val maxMonths: Int)

/** An ISO-8601 moment (with or without fractions), in epoch millis; null when unreadable. */
fun isoMillis(text: String?): Long? =
    text?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Result of a network auth call: success, or a human error message. */
sealed interface AuthOutcome {
    data class Success(val result: AuthResult) : AuthOutcome
    data class Failure(val message: String) : AuthOutcome
}

/** Client for accounts, auth, sessions and profile (`/api/accounts`). */
class AccountApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = "${baseUrl.trimEnd('/')}$path"

    /**
     * Whether [username] is free. Signed in ([token]), a name the driver left lately counts as
     * theirs again. Null when the backend could not say.
     */
    suspend fun usernameAvailability(username: String, token: String?): UsernameAvailability? = withContext(Dispatchers.IO) {
        val u = java.net.URLEncoder.encode(username, "UTF-8")
        val req = Request.Builder().url(url("/api/accounts/username-available?u=$u"))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string() ?: "")
                UsernameAvailability.fromWire(o.optBoolean("available"), o.optString("reason").ifBlank { null }.takeUnless { o.isNull("reason") })
            }
        }.getOrNull()
    }

    /** Legacy device sign-in (used to restore a session by deviceId); [app]: what the phone says of itself. */
    suspend fun authDevice(deviceId: String, app: Map<String, String> = emptyMap()): AuthResult? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("deviceId", deviceId).put("platform", "android")
        if (app.isNotEmpty()) payload.put("app", JSONObject(app))
        post("/api/accounts/auth", payload).let { (ok, body) -> if (ok) parseAuth(body) else null }
    }

    suspend fun claimGuest(deviceId: String, username: String, password: String, app: Map<String, String> = emptyMap()): AuthOutcome =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("deviceId", deviceId).put("username", username).put("password", password).put("platform", "android")
            if (app.isNotEmpty()) payload.put("app", JSONObject(app))
            outcome("/api/accounts/guest", payload)
        }

    suspend fun register(
        email: String,
        password: String,
        username: String,
        referralCode: String?,
        app: Map<String, String> = emptyMap(),
    ): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("email", email).put("password", password).put("username", username)
        if (!referralCode.isNullOrBlank()) payload.put("referralCode", referralCode.trim())
        // What the app knows of itself, for the admin card: nothing read behind the driver's back.
        if (app.isNotEmpty()) payload.put("app", JSONObject(app))
        outcome("/api/accounts/register", payload)
    }

    // ---- Sign in with Google --------------------------------------------------

    /** The identity token from Google, checked on the server. Never trusted here. */
    suspend fun signInWithGoogle(idToken: String, deviceId: String?, app: Map<String, String>): AuthOutcome =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("idToken", idToken).put("app", JSONObject(app))
            if (!deviceId.isNullOrBlank()) payload.put("deviceId", deviceId)
            outcome("/api/accounts/google", payload)
        }

    /** Ties a Google account to the one signed in; null when it went through. */
    suspend fun linkGoogle(idToken: String, token: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me/link/google"))
            .header("Authorization", "Bearer $token")
            .post(JSONObject().put("idToken", idToken).toString().toRequestBody(JSON))
            .build()
        simpleCall(req, "Liaison impossible pour l'instant.")
    }

    /** Unties it; null when it went through. */
    suspend fun unlinkGoogle(token: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me/link/google")).header("Authorization", "Bearer $token").delete().build()
        simpleCall(req, "Dissociation impossible pour l'instant.")
    }

    /** Whether the other members of a group trip see this driver's statistics on their card. */
    suspend fun setGroupStatsVisible(visible: Boolean, token: String): AuthOutcome = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me/privacy"))
            .header("Authorization", "Bearer $token")
            .patch(JSONObject().put("groupStatsVisible", visible).toString().toRequestBody(JSON))
            .build()
        runOutcome(req)
    }

    private fun simpleCall(req: Request, offline: String): String? = try {
        client.newCall(req).execute().use { r -> if (r.isSuccessful) null else friendly(errorRaw(r.body?.string())) }
    } catch (e: Exception) {
        offline
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
            .put("stops", trip.stops)
            .put("stoppedSeconds", trip.stoppedSeconds)
            .put("events", wireEvents(trip))
            .apply { trip.plannedSeconds?.let { put("plannedSeconds", it) } }
        authedPost(token, "/api/accounts/me/trips", body)
    }

    /** The version of the terms accepted, and when: the backend stamps the moment itself. */
    suspend fun recordTerms(token: String, version: String): Boolean = withContext(Dispatchers.IO) {
        authedPost(token, "/api/accounts/me/terms", JSONObject().put("version", version))
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
                    plannedSeconds = if (x.has("plannedSeconds") && !x.isNull("plannedSeconds")) x.optInt("plannedSeconds") else null,
                    stops = x.optInt("stops"),
                    stoppedSeconds = x.optInt("stoppedSeconds"),
                    events = parseEvents(x.optJSONObject("events")),
                )
            },
        )
    }

    /** How long new codes stay usable, and the range an admin may pick from. */
    suspend fun referralSettings(token: String): ReferralSettings? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/referrals/settings")).header("Authorization", "Bearer $token").build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val s = JSONObject(r.body?.string() ?: "").optJSONObject("settings") ?: return@use null
                ReferralSettings(
                    validityMonths = s.optInt("referralValidityMonths", 3),
                    minMonths = s.optInt("referralValidityMinMonths", 1),
                    maxMonths = s.optInt("referralValidityMaxMonths", 12),
                )
            }
        }.getOrNull()
    }

    /** Sets the duration for the codes minted from now on; the ones already out keep their date. */
    suspend fun setReferralValidity(months: Int, token: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/referrals/settings"))
            .header("Authorization", "Bearer $token")
            .put(JSONObject().put("validityMonths", months).toString().toRequestBody(JSON))
            .build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** "extend" (with months), "revoke" or "regenerate" on one code. */
    suspend fun actOnReferral(code: String, action: String, months: Int?, token: String): ReferralCode? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("action", action).apply { months?.let { put("months", it) } }
        val req = Request.Builder().url(url("/api/accounts/referrals/" + java.net.URLEncoder.encode(code, "UTF-8")))
            .header("Authorization", "Bearer $token")
            .patch(body.toString().toRequestBody(JSON))
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: "").optJSONObject("referral")?.let(::parseReferral)
            }
        }.getOrNull()
    }

    private fun parseReferral(o: JSONObject): ReferralCode {
        val history = o.optJSONArray("history")
        return ReferralCode(
            code = o.optString("code"),
            createdAt = o.optString("createdAt"),
            months = o.optInt("months", 6),
            redemptions = o.optInt("redemptions"),
            active = o.optBoolean("active", true),
            validityMonths = if (o.has("validityMonths") && !o.isNull("validityMonths")) o.optInt("validityMonths") else null,
            expiresAt = o.optString("expiresAt").ifBlank { null }.takeUnless { o.isNull("expiresAt") },
            revokedAt = o.optString("revokedAt").ifBlank { null }.takeUnless { o.isNull("revokedAt") },
            history = (0 until (history?.length() ?: 0)).mapNotNull { i ->
                val e = history?.optJSONObject(i) ?: return@mapNotNull null
                ReferralEvent(e.optString("action"), e.optString("by"), e.optString("at").ifBlank { null }.takeUnless { e.isNull("at") })
            },
        )
    }

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
        limits = o.optJSONObject("limits")?.let(::parseLimits),
        canChangeUsername = o.optBoolean("canChangeUsername"),
        usernameChangeableAt = o.optString("usernameChangeableAt").ifBlank { null }.takeUnless { o.isNull("usernameChangeableAt") },
        signupMethod = o.optString("signupMethod").ifBlank { null }.takeUnless { o.isNull("signupMethod") },
        providers = o.optJSONArray("providers")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("provider")?.ifBlank { null } }
        } ?: emptyList(),
        hasPassword = o.optBoolean("hasPassword", true),
        groupStatsVisible = o.optBoolean("groupStatsVisible", true),
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

    /** Deletes the account for good; null on success, else a message the driver can read. */
    suspend fun deleteAccount(token: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me"))
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        try {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) null else friendly(errorRaw(r.body?.string())) }
        } catch (e: Exception) {
            "Réseau indisponible"
        }
    }

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
        err.contains("reserved") -> "Ce pseudo est réservé."
        err.contains("change too soon") -> "Un seul changement de pseudo par semaine."
        err.contains("clients only") -> "Réservé aux membres avec un accès actif."
        err.contains("invalid username") -> "Pseudo invalide (3–20 caractères : lettres, chiffres, _ .)."
        err.contains("email already") -> "Cet email est déjà utilisé."
        err.contains("invalid email") -> "Email invalide."
        err.contains("password too short") -> "Mot de passe trop court (8 caractères min)."
        err.contains("invalid credentials") -> "Identifiant ou mot de passe incorrect."
        err.contains("banned") -> "Ce compte est banni."
        err.contains("invalid referral") -> "Code de parrainage invalide."
        err.contains("subscription required") -> "Abonnement requis."
        err.contains("google sign-in not configured") -> "Connexion Google indisponible pour l'instant."
        err.contains("already linked") -> "Ce compte Google est déjà lié à un autre compte EONA."
        err.contains("set a password first") -> "Impossible : ce serait ton seul moyen de te connecter."
        err.contains("not linked") -> "Aucun compte Google n'est lié."
        else -> err.replaceFirstChar { it.uppercase() }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** A trip's events as the backend keeps them: counts by kind name. */
        fun wireEvents(trip: TripRecord): JSONObject =
            JSONObject().apply { trip.events.forEach { (type, count) -> put(type.wireName, count) } }

        /** A trip's events read back; unknown kinds and empty counts are left out. */
        fun parseEvents(o: JSONObject?): Map<com.eona.app.core.model.AlertType, Int> {
            if (o == null) return emptyMap()
            return o.keys().asSequence().mapNotNull { name ->
                val type = alertTypeFromWire(name) ?: return@mapNotNull null
                val count = o.optInt(name)
                if (count > 0) type to count else null
            }.toMap()
        }

        /** A guest's daily limits, as `/me` sends them (and as the account is cached). */
        fun parseLimits(o: JSONObject) = DailyLimits(
            day = o.optString("day"),
            reportsPerDay = o.optInt("reportsPerDay"),
            reportsToday = o.optInt("reportsToday"),
            tripsPerDay = o.optInt("tripsPerDay"),
            tripsToday = o.optInt("tripsToday"),
        )
    }
}
