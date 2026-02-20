package com.seanshubin.vote.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("SecretBallot")
data class SecretBallot(
    val electionName: String,
    val confirmation: String,
    override val rankings: List<Ranking>
) : Ballot
