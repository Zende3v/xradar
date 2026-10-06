package com.eona.app.data.account

import android.content.Context
import android.content.SharedPreferences
import com.eona.app.core.model.Account
import com.eona.app.core.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Identité, session, droits (iOS AccountStore). Invité permanent avec pseudo aléatoire ; membre
 * avec email ou fournisseur. Jeton et dernier compte gardés pour ouvrir l'app sans flash.
 */
object AccountRepository {

    private val api = AccountApi()
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null
    private var sessionRevision = 0

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _hasPlus = MutableStateFlow(false)

    /** EONA+ en cours : essai, abonnement ou admin. Échéance passée : faux sans attendre le serveur. */
    val hasPlus: StateFlow<Boolean> = _hasPlus.asStateFlow()

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
        scheduleExpiry()
    }

    /** Vérifie la session. Refus confirmé : identité effacée ; hors réseau : cache gardé. */
    suspend fun refresh(context: Context) {
        val id = ensureDeviceId(context)
        val t = token
        if (t != null) {
            val fresh = try {
                api.me(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            if (token != t) return
            if (fresh != null) {
                store(fresh, t)
                return
            }
            clearSession()
        }
        // Compte membre : connexion explicite. Appareil seul : invité.
        val cached = _account.value
        if (cached != null && cached.role != Role.Guest) clearSession()
        val revision = sessionRevision
        api.authDevice(id, appInfo)?.let { auth ->
            if (token == null && sessionRevision == revision) store(auth.account, auth.token)
        }
    }

    /**
     * What the app knows of itself, joined to a sign-up so the admin card is not empty: the phone's
     * model, its system, our version, the language of the device. Nothing more, and nothing the
     * system does not hand over freely — no advertising identifier.
     */
    val appInfo: Map<String, String>
        get() {
            val locale = java.util.Locale.getDefault()
            return mapOf(
                "platform" to "android",
                "model" to listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
                "osVersion" to android.os.Build.VERSION.RELEASE.orEmpty(),
                "appVersion" to com.eona.app.BuildConfig.VERSION_NAME,
                "locale" to locale.toLanguageTag(),
                "region" to locale.country.orEmpty(),
            ).filterValues { it.isNotBlank() }
        }

    /** Google's identity token, checked by the server; the account it opens sticks to this phone. */
    suspend fun signInWithGoogle(idToken: String): AuthOutcome =
        api.signInWithGoogle(idToken, deviceId, appInfo).also(::applyOutcome)

    /** Ties Google to the account; null when done (the account is re-read for its providers). */
    suspend fun linkGoogle(idToken: String): String? {
        val t = token ?: return "Non connecté"
        api.linkGoogle(idToken, t)?.let { return it }
        reload()
        return null
    }

    suspend fun unlinkGoogle(): String? {
        val t = token ?: return "Non connecté"
        api.unlinkGoogle(t)?.let { return it }
        reload()
        return null
    }

    /** Whether the other members of a group trip see this driver's statistics on their card. */
    suspend fun setGroupStatsVisible(visible: Boolean): AuthOutcome {
        val t = token ?: return AuthOutcome.Failure("Non connecté")
        return api.setGroupStatsVisible(visible, t).also(::applyOutcome)
    }

    suspend fun referralSettings(): ReferralSettings? = token?.let { api.referralSettings(it) }

    suspend fun setReferralValidity(months: Int): Boolean = token?.let { api.setReferralValidity(months, it) } ?: false

    suspend fun actOnReferral(code: String, action: String, months: Int? = null): ReferralCode? =
        token?.let { api.actOnReferral(code, action, months, it) }

    /** The account behind the token, or null when it could not be read (offline, server error, invalid). */
    private suspend fun meOrNull(t: String): Account? = try {
        api.me(t)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Whether [username] is free (a name this driver left lately is theirs); null when unknown. */
    suspend fun usernameAvailability(username: String): com.eona.app.core.model.UsernameAvailability? =
        api.usernameAvailability(username, token)

    /** « Continuer en tant qu'invité » : pseudo aléatoire, sans mot de passe. */
    suspend fun claimGuest(): AuthOutcome =
        api.claimGuest(ensureDeviceIdOrEmpty(), appInfo).also(::applyOutcome)

    /** Inscription ; invité courant converti : identifiant, historique et statistiques gardés. */
    suspend fun register(email: String, password: String, username: String, referralCode: String? = null): AuthOutcome {
        val current = _account.value
        val guestToken = token.takeIf { current?.isGuest == true && current.isOnboarded }
        return api.register(email, password, username, referralCode, appInfo, deviceId, guestToken).also(::applyOutcome)
    }

    suspend fun stats(): AccountStats? = token?.let { api.stats(it) }

    suspend fun postTrip(trip: com.eona.app.core.model.TripRecord): Boolean =
        token?.let { api.postTrip(it, trip) } ?: false

    /** The terms the driver accepted: the account keeps the version and the moment, as proof. */
    suspend fun recordTerms(version: String): Boolean = token?.let { api.recordTerms(it, version) } ?: false

    suspend fun postDrive(seconds: Int, meters: Int): Boolean =
        token?.let { api.postDrive(it, seconds, meters) } ?: false

    suspend fun referrals(): List<ReferralCode> = token?.let { api.referrals(it) } ?: emptyList()

    suspend fun createReferral(): ReferralCode? = token?.let { api.createReferral(it) }

    /** Relit le compte (l'accès bouge : essai fini, parrainage…). Refus confirmé : session effacée. */
    suspend fun reload() {
        val t = token ?: return
        val fresh = try {
            api.me(t)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (token != t) return
        if (fresh != null) store(fresh, t) else clearSession()
    }

    /** Email (member) or username (guest), and the password; the account then sticks to this phone. */
    suspend fun login(identifier: String, password: String): AuthOutcome =
        api.login(identifier, password, deviceId).also(::applyOutcome)

    suspend fun updateProfile(username: String? = null, avatarUrl: String? = null): AuthOutcome {
        val t = token ?: return AuthOutcome.Failure("Non connecté")
        return api.updateProfile(t, username, avatarUrl).also(::applyOutcome)
    }

    suspend fun uploadAvatar(dataUrl: String): AuthOutcome {
        val t = token ?: return AuthOutcome.Failure("Non connecté")
        return api.uploadAvatar(t, dataUrl).also(::applyOutcome)
    }

    suspend fun forgot(email: String) = api.forgot(email)

    suspend fun resetPassword(email: String, code: String, password: String): String? =
        api.resetPassword(email, code, password)

    suspend fun verifyEmail(code: String): String? {
        val email = _account.value?.email ?: return "Aucun email"
        val err = api.verify(email, code)
        if (err == null) token?.let { t -> meOrNull(t)?.let { store(it, t) } } // refresh emailVerified
        return err
    }

    suspend fun resendVerify() {
        _account.value?.email?.let { api.resendVerify(it) }
    }

    /**
     * Deletes the account on the server, then forgets the session here (the phone keeps its id).
     * Null on success, else a message the driver can read.
     */
    suspend fun deleteAccount(): String? {
        val t = token ?: return "Non connecté"
        api.deleteAccount(t)?.let { return it }
        clearSession()
        return null
    }

    fun logout() {
        token?.let { t -> scope.launch { api.logout(t) } }
        clearSession()
    }

    private fun clearSession() {
        expiryJob?.cancel()
        setToken(null)
        _account.value = null
        _hasPlus.value = false
        prefs?.edit()?.remove(KEY_ACCOUNT)?.apply()
    }

    /** Droits relus à l'échéance. Hors réseau, l'heure locale coupe déjà les avantages expirés. */
    private fun scheduleExpiry() {
        expiryJob?.cancel()
        val current = _account.value
        _hasPlus.value = current?.hasPlus() == true
        val end = isoMillis(current?.accessEndsAt) ?: return
        val wait = end - System.currentTimeMillis()
        if (wait <= 0) return
        expiryJob = scope.launch {
            delay(wait + 100)
            _hasPlus.value = _account.value?.hasPlus() == true
            reload()
        }
    }

    private fun applyOutcome(outcome: AuthOutcome) {
        if (outcome is AuthOutcome.Success) store(outcome.result.account, outcome.result.token)
    }

    private fun store(account: Account, newToken: String?) {
        _account.value = account
        scheduleExpiry()
        if (newToken != null) setToken(newToken)
        prefs?.edit()?.putString(KEY_ACCOUNT, accountToJson(account).toString())?.apply()
    }

    private fun setToken(value: String?) {
        sessionRevision += 1
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
        put("emailVerified", a.emailVerified)
        put("access", a.access.name.lowercase())
        put("canNavigate", a.canNavigate)
        put("accessEndsAt", a.accessEndsAt)
        put("trust", a.trust)
        put("canChangeUsername", a.canChangeUsername)
        put("usernameChangeableAt", a.usernameChangeableAt)
        put("signupMethod", a.signupMethod)
        put("providers", org.json.JSONArray(a.providers.map { JSONObject().put("provider", it) }))
        put("hasPassword", a.hasPassword)
        put("groupStatsVisible", a.groupStatsVisible)
        a.limits?.let { l ->
            put(
                "limits",
                JSONObject()
                    .put("day", l.day)
                    .put("reportsPerDay", l.reportsPerDay)
                    .put("reportsToday", l.reportsToday)
                    .put("tripsPerDay", l.tripsPerDay)
                    .put("tripsToday", l.tripsToday),
            )
        }
    }

    private fun accountFromJson(o: JSONObject) = Account(
        id = o.optString("id"),
        role = Role.fromWire(o.optString("role")),
        username = o.optString("username").ifBlank { null }.takeUnless { o.isNull("username") },
        displayName = o.optString("displayName").ifBlank { null }.takeUnless { o.isNull("displayName") },
        avatarUrl = o.optString("avatarUrl").ifBlank { null }.takeUnless { o.isNull("avatarUrl") },
        email = o.optString("email").ifBlank { null }.takeUnless { o.isNull("email") },
        banned = o.optBoolean("banned"),
        emailVerified = o.optBoolean("emailVerified"),
        access = com.eona.app.core.model.Access.fromWire(o.optString("access")),
        canNavigate = if (o.has("canNavigate")) o.optBoolean("canNavigate") else true,
        accessEndsAt = o.optString("accessEndsAt").ifBlank { null }.takeUnless { o.isNull("accessEndsAt") },
        trust = o.optDouble("trust", 2.5),
        limits = o.optJSONObject("limits")?.let(AccountApi::parseLimits),
        // A cache from before username changes: no rename until the next refresh.
        canChangeUsername = o.optBoolean("canChangeUsername"),
        usernameChangeableAt = o.optString("usernameChangeableAt").ifBlank { null }.takeUnless { o.isNull("usernameChangeableAt") },
        signupMethod = o.optString("signupMethod").ifBlank { null }.takeUnless { o.isNull("signupMethod") },
        providers = o.optJSONArray("providers")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("provider")?.ifBlank { null } }
        } ?: emptyList(),
        hasPassword = o.optBoolean("hasPassword", true),
        // Absent from a cache written before member cards: visible.
        groupStatsVisible = o.optBoolean("groupStatsVisible", true),
    )

    private const val KEY_DEVICE = "device_id"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_ACCOUNT = "account_json"
}
