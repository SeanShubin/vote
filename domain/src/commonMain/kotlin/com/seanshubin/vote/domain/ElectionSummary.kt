package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class ElectionSummary(
    val ownerName: String,
    val electionName: String,
    val description: String = "",
) {
    fun toElectionDetail(
        candidateCount: Int,
        ballotCount: Int,
        tiers: List<String> = emptyList(),
    ): ElectionDetail =
        ElectionDetail(
            ownerName = ownerName,
            electionName = electionName,
            description = description,
            candidateCount = candidateCount,
            ballotCount = ballotCount,
            tiers = tiers,
        )
}
