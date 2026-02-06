package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class SetCandidatesRequest(
    val candidateNames: List<String>
)
