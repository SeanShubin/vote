package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String
)
