package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class ClientErrorRequest(
    val message: String,
    val stackTrace: String?,
    val url: String,
    val userAgent: String,
    val timestamp: String
)
