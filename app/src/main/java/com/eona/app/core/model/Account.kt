package com.eona.app.core.model

/** Identité : invité, membre inscrit, administrateur. EONA+ dépend de l'accès, jamais du rôle seul. */
enum class Role(val label: String) {
    Guest("Invité"),
    Client("Membre"),
    Admin("Admin");

    companion object {
        fun fromWire(value: String?): Role = when (value) {
            "admin" -> Admin
            "client" -> Client
            else -> Guest
        }
    }
}

/** Where the account stands with regard to access, as the backend computes it. */
enum class Access {
    Free,

    /** Inscrit pendant sept jours EONA+ offerts. */
    Trial,

    /** Client with a running subscription, or admin. */
    Active,

    /** Compte bloqué. Essai expiré conserve accès gratuit. */
    Restricted;

    companion object {
        fun fromWire(value: String?): Access = when (value) {
            "active" -> Active
            "restricted" -> Restricted
            "trial" -> Trial
            else -> Free
        }
    }
}

/** The signed-in account. Pure model. */
data class Account(
    val id: String,
    val role: Role,
    val username: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val email: String?,
    val banned: Boolean,
    val emailVerified: Boolean = false,
    val access: Access = Access.Free,
    /** Faux seulement quand accès compte est bloqué. */
    val canNavigate: Boolean = true,
    /** Fin d'essai ou abonnement, ISO-8601 ; null = aucune échéance. */
    val accessEndsAt: String? = null,
    /** "Note de confiance", 0..5 — how often this driver's reports get confirmed. */
    val trust: Double = 2.5,
    /** A guest's daily limits and today's use; null for clients and admins, who have none. */
    val limits: DailyLimits? = null,
    /** "Changer de pseudo": a client whose access runs, as the backend says. */
    val canChangeUsername: Boolean = false,
    /** When the username may change again (ISO-8601, once a week); null = now. */
    val usernameChangeableAt: String? = null,
    /** How the account was opened: "email", "device" or a provider ("google"). */
    val signupMethod: String? = null,
    /** The providers this account can sign in with, as they are named on the wire. */
    val providers: List<String> = emptyList(),
    /** True when a password can still open it: unlinking a provider is then harmless. */
    val hasPassword: Boolean = true,
    /** In a group trip, the other members see this driver's statistics on their card. */
    val groupStatsVisible: Boolean = true,
) {
    /** A finished onboarding = has a chosen username. */
    val isOnboarded: Boolean get() = !username.isNullOrBlank()

    /** Profile pictures are for members (renames: [canChangeUsername]). */
    val canEditProfile: Boolean get() = !banned && (role == Role.Client || role == Role.Admin)

    val isRestricted: Boolean get() = banned || access == Access.Restricted || !canNavigate

    /** EONA+ en cours : essai, abonnement ou admin. */
    val isSubscriber: Boolean get() = hasPlus()

    /** Invité : ni email ni fournisseur. */
    val isGuest: Boolean get() = role == Role.Guest && email == null && providers.isEmpty()

    /** Essai expiré : gratuit immédiat, même avant prochain rafraîchissement serveur. */
    fun hasPlus(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (isRestricted) return false
        if (role == Role.Admin) return true
        if (isGuest || (access != Access.Trial && access != Access.Active)) return false
        if (accessEndsAt != null) {
            val end = runCatching { java.time.OffsetDateTime.parse(accessEndsAt).toInstant().toEpochMilli() }.getOrNull() ?: return false
            return end > nowMillis
        }
        return access == Access.Active
    }
}

/** What the backend says of a username someone wants. */
enum class UsernameAvailability {
    Available,
    Taken,
    /** Kept for the team ("admin", "support", "eona…"). */
    Reserved,
    Invalid;

    companion object {
        fun fromWire(available: Boolean, reason: String?): UsernameAvailability = when {
            available -> Available
            reason == "reserved" -> Reserved
            reason == "invalid username" -> Invalid
            else -> Taken
        }
    }
}

/** The backend's rules for a username, checked before asking it anything. */
object UsernameRules {
    private val WELL_FORMED = Regex("^[a-zA-Z0-9_.]{3,20}$")

    /** 3 to 20 letters (no accents), digits, "_" and ".". */
    fun isWellFormed(name: String): Boolean = WELL_FORMED.matches(name)
}
