package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class ElectionSummary(
    val ownerName: String,
    val electionName: String,
) {
    fun toElectionDetail(candidateCount: Int, ballotCount: Int): ElectionDetail =
        ElectionDetail(
            ownerName = ownerName,
            electionName = electionName,
            candidateCount = candidateCount,
            ballotCount = ballotCount,
        )
}
