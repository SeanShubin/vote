package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserUpdates(
    val userName: String? = null,
    val email: String? = null
)
