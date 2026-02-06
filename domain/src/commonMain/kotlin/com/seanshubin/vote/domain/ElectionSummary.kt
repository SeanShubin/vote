package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ElectionSummary(
    val ownerName: String,
    val electionName: String,
    val secretBallot: Boolean = true,
    val noVotingBefore: Instant? = null,
    val noVotingAfter: Instant? = null,
    val allowEdit: Boolean = true,
    val allowVote: Boolean = false
) {
    fun toElectionDetail(candidateCount: Int, voterCount: Int): ElectionDetail =
        ElectionDetail(
            ownerName,
            electionName,
            candidateCount,
            voterCount,
            secretBallot,
            noVotingBefore,
            noVotingAfter,
            allowEdit,
            allowVote
        )
}
