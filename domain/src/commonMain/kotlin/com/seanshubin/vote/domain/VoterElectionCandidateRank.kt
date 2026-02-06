package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class VoterElectionCandidateRank(
    val voter: String,
    val election: String,
    val candidate: String,
    val rank: Int
)
