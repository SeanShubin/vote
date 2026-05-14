package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class AddCandidatesRequest(
    val candidateNames: List<String>
)
