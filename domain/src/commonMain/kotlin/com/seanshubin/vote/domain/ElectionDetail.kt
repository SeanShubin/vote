package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ElectionDetail(
    val ownerName: String,
    val electionName: String,
    val candidateCount: Int,
    val voterCount: Int,
    val secretBallot: Boolean = true,
    val noVotingBefore: Instant? = null,
    val noVotingAfter: Instant? = null,
    val allowEdit: Boolean = true,
    val allowVote: Boolean = false,
)
