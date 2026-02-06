package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Permission {
    TRANSFER_OWNER,
    VIEW_SECRETS,
    MANAGE_USERS,
    USE_APPLICATION,
    VOTE,
    VIEW_APPLICATION
}
