package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class AddElectionRequest(
    val userName: String,
    val electionName: String
)
