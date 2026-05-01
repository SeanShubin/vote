package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Returned by GET /election/{name}. The header on the SPA's election detail
 * page binds to this — owner + the count of candidates and ballots cast.
 */
@Serializable
data class ElectionDetail(
    val ownerName: String,
    val electionName: String,
    val candidateCount: Int,
    val ballotCount: Int,
    val description: String = "",
)
