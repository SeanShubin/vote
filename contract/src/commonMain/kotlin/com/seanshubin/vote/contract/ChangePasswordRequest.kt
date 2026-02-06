package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class ChangePasswordRequest(
    val password: String
)
