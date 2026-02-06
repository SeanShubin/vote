package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val name: String,
    val email: String,
    val salt: String,
    val hash: String,
    val role: Role
)
