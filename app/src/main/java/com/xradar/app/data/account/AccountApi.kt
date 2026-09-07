package com.xradar.app.data.account

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
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

    suspend fun claimGuest(deviceId: String, username: String): AuthOutcome = withContext(Dispatchers.IO) {
        outcome("/api/accounts/guest", JSONObject().put("deviceId", deviceId).put("username", username).put("platform", "android"))
    }

    suspend fun register(email: String, password: String, username: String): AuthOutcome = withContext(Dispatchers.IO) {
        outcome("/api/accounts/register", JSONObject().put("email", email).put("password", password).put("username", username))
    }

    suspend fun login(email: String, password: String): AuthOutcome = withContext(Dispatchers.IO) {
        outcome("/api/accounts/login", JSONObject().put("email", email).put("password", password))
    }

    /** Refresh the account from a session token; null if the token is invalid. */
    suspend fun me(token: String): Account? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/accounts/me")).header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val body = r.body?.string() ?: return@use null
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
    )

    private fun errorMessage(body: String): String = runCatching {
        JSONObject(body).optString("error").ifBlank { "Erreur" }
    }.getOrDefault("Erreur").let(::friendly)

    private fun friendly(err: String): String = when {
        err.contains("taken") -> "Ce pseudo est déjà pris."
        err.contains("invalid username") -> "Pseudo invalide (3–20 caractères : lettres, chiffres, _ .)."
        err.contains("email already") -> "Cet email est déjà utilisé."
        err.contains("invalid email") -> "Email invalide."
        err.contains("password too short") -> "Mot de passe trop court (8 caractères min)."
        err.contains("invalid credentials") -> "Email ou mot de passe incorrect."
        err.contains("banned") -> "Ce compte est banni."
        else -> err.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
