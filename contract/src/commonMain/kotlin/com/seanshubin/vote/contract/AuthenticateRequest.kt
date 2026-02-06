package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class AuthenticateRequest(
    val nameOrEmail: String,
    val password: String
)
