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
) {
    /** A finished onboarding = has a chosen username. */
    val isOnboarded: Boolean get() = !username.isNullOrBlank()

    /** Profile pictures and renames are for members. */
    val canEditProfile: Boolean get() = role == Role.Client || role == Role.Admin

    val isRestricted: Boolean get() = access == Access.Restricted || !canNavigate
}
