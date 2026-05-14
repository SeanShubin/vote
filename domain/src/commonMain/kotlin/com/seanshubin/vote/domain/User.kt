package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Projection row for a user. With Discord-only login the only credential
 * carried inline is the Discord identity ([discordId] + [discordDisplayName]).
 * An empty [discordId] would never happen in practice; the field is required
 * because every user is created via the Discord OAuth callback. Defaults on
 * the credential fields preserve readability of older serialized blobs.
 */
@Serializable
data class User(
    val name: String,
    val role: Role,
    val discordId: String = "",
    val discordDisplayName: String = "",
)
