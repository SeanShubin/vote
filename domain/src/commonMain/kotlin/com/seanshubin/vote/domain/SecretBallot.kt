package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class SecretBallot(
    val electionName: String,
    val confirmation: String,
    override val rankings: List<Ranking>
) : Ballot
