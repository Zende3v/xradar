package com.xradar.app.core.model

/**
 * Account role. Guest = free trial (7 days) then restricted until they pay; Client =
 * paying subscriber (or referral); Admin = full access.
 */
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
    /** Guest within the 7 free days. */
    Trial,

    /** Client with a running subscription, or admin. */
    Active,

    /** Trial over / subscription lapsed: map only, no navigation, no reporting. */
    Restricted;

    companion object {
        fun fromWire(value: String?): Access = when (value) {
            "active" -> Active
            "restricted" -> Restricted
            else -> Trial
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
    val access: Access = Access.Trial,
    /** False once the trial or the subscription has run out. */
    val canNavigate: Boolean = true,
    /** End of the trial (guest) or of the subscription (client), ISO-8601; null = none. */
    val accessEndsAt: String? = null,
    /** "Note de confiance", 0..5 — how often this driver's reports get confirmed. */
    val trust: Double = 2.5,
    /** A guest's daily limits and today's use; null for clients and admins, who have none. */
    val limits: DailyLimits? = null,
    /** "Changer de pseudo": a client whose access runs, as the backend says. */
    val canChangeUsername: Boolean = false,
    /** When the username may change again (ISO-8601, once a week); null = now. */
    val usernameChangeableAt: String? = null,
) {
    /** A finished onboarding = has a chosen username. */
    val isOnboarded: Boolean get() = !username.isNullOrBlank()

    /** Profile pictures are for members (renames: [canChangeUsername]). */
    val canEditProfile: Boolean get() = role == Role.Client || role == Role.Admin

    val isRestricted: Boolean get() = access == Access.Restricted || !canNavigate

    /** A client whose subscription runs, or an admin. */
    val isSubscriber: Boolean get() = (role == Role.Client || role == Role.Admin) && !isRestricted
}

/** What the backend says of a username someone wants. */
enum class UsernameAvailability {
    Available,
    Taken,
    /** Kept for the team ("admin", "support", "xradar…"). */
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
