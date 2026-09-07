package com.xradar.app.core.model

/** Account role. Guests are local-only; clients/admins are assigned server-side. */
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

/** The signed-in account. Pure model. */
data class Account(
    val id: String,
    val role: Role,
    val username: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val email: String?,
    val banned: Boolean,
) {
    /** A finished onboarding = has a chosen username. */
    val isOnboarded: Boolean get() = !username.isNullOrBlank()

    val canEditProfile: Boolean get() = role == Role.Client || role == Role.Admin
}
