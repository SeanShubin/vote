package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Row shape returned by `listUsers` to admin UIs. Carries credential
 * info so the UI can show the Discord display name next to the username.
 * [discordDisplayName] empty means "no Discord credential on this user".
 * [discordDisplayName] alone (without id) would never happen — both come
 * from the same Discord callback and write atomically.
 */
@Serializable
data class UserNameRole(
    val userName: String,
    val role: Role,
    val allowedRoles: List<Role>,
    val discordId: String = "",
    val discordDisplayName: String = "",
)
