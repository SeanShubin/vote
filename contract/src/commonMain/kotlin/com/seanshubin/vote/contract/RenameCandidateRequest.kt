package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class RenameCandidateRequest(
    val oldName: String,
    val newName: String,
)
