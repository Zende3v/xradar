package com.xradar.app.data.account

import android.content.Context
import android.content.SharedPreferences
import com.xradar.app.core.model.Account
import com.xradar.app.core.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

/**
 * App-scoped identity + session. A per-install device id restores a session; a
 * chosen username (guest) or email/password (client/admin) completes onboarding.
 * The session token + last account are cached so the app opens without a flash.
 */
object AccountRepository {

    private val api = AccountApi()
    private var prefs: SharedPreferences? = null

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    var deviceId: String? = null
        private set
    var token: String? = null
        private set

    val role: Role get() = _account.value?.role ?: Role.Guest

    fun ensureDeviceId(context: Context): String {
        deviceId?.let { return it }
        val p = prefs(context)
        val id = p.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_DEVICE, it).apply()
        }
        deviceId = id
        return id
    }

    /** Load the cached session synchronously so the UI can render immediately. */
    fun restore(context: Context) {
        ensureDeviceId(context)
        val p = prefs(context)
        token = p.getString(KEY_TOKEN, null)
        p.getString(KEY_ACCOUNT, null)?.let { raw ->
            runCatching { _account.value = accountFromJson(JSONObject(raw)) }
        }
    }

    /** Refresh from the backend: validate the token, else restore a device session. */
    suspend fun refresh(context: Context) {
        val id = ensureDeviceId(context)
        val t = token
        if (t != null) {
            val fresh = api.me(t)
            if (fresh != null) {
                store(fresh, t)
                return
            }
            setToken(null) // token no longer valid
        }
        api.authDevice(id)?.let { store(it.account, it.token) }
    }

    suspend fun usernameAvailable(username: String): Boolean = api.usernameAvailable(username)

    suspend fun claimGuest(username: String): AuthOutcome =
        api.claimGuest(ensureDeviceIdOrEmpty(), username).also(::applyOutcome)

    suspend fun register(email: String, password: String, username: String): AuthOutcome =
        api.register(email, password, username).also(::applyOutcome)

    suspend fun login(email: String, password: String): AuthOutcome =
        api.login(email, password).also(::applyOutcome)

    suspend fun updateProfile(username: String? = null, avatarUrl: String? = null): AuthOutcome {
        val t = token ?: return AuthOutcome.Failure("Non connecté")
        return api.updateProfile(t, username, avatarUrl).also(::applyOutcome)
    }

    suspend fun uploadAvatar(dataUrl: String): AuthOutcome {
        val t = token ?: return AuthOutcome.Failure("Non connecté")
        return api.uploadAvatar(t, dataUrl).also(::applyOutcome)
    }

    fun logout() {
        setToken(null)
        _account.value = null
        prefs?.edit()?.remove(KEY_ACCOUNT)?.apply()
    }

    private fun applyOutcome(outcome: AuthOutcome) {
        if (outcome is AuthOutcome.Success) store(outcome.result.account, outcome.result.token)
    }

    private fun store(account: Account, newToken: String?) {
        _account.value = account
        if (newToken != null) setToken(newToken)
        prefs?.edit()?.putString(KEY_ACCOUNT, accountToJson(account).toString())?.apply()
    }

    private fun setToken(value: String?) {
        token = value
        prefs?.edit()?.apply { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }?.apply()
    }

    private fun ensureDeviceIdOrEmpty() = deviceId ?: ""

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences("xr_identity", Context.MODE_PRIVATE).also { prefs = it }

    private fun accountToJson(a: Account) = JSONObject().apply {
        put("id", a.id)
        put("role", a.role.name.lowercase())
        put("username", a.username)
        put("displayName", a.displayName)
        put("avatarUrl", a.avatarUrl)
        put("email", a.email)
        put("banned", a.banned)
    }

    private fun accountFromJson(o: JSONObject) = Account(
        id = o.optString("id"),
        role = Role.fromWire(o.optString("role")),
        username = o.optString("username").ifBlank { null }.takeUnless { o.isNull("username") },
        displayName = o.optString("displayName").ifBlank { null }.takeUnless { o.isNull("displayName") },
        avatarUrl = o.optString("avatarUrl").ifBlank { null }.takeUnless { o.isNull("avatarUrl") },
        email = o.optString("email").ifBlank { null }.takeUnless { o.isNull("email") },
        banned = o.optBoolean("banned"),
    )

    private const val KEY_DEVICE = "device_id"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_ACCOUNT = "account_json"
}
