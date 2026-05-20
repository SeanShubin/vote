package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class RenameElectionRequest(
    val newName: String,
)
