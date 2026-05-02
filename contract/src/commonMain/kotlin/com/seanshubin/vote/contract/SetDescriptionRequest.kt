package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class SetDescriptionRequest(
    val description: String,
)
