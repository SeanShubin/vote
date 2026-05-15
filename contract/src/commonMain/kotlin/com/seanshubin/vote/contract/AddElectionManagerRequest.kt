package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class AddElectionManagerRequest(
    val userName: String,
)
