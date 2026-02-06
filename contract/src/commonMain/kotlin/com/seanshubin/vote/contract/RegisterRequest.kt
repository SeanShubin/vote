package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val userName: String,
    val email: String,
    val password: String
)
