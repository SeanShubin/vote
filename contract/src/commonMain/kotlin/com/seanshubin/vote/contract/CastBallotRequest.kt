package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.Ranking
import kotlinx.serialization.Serializable

@Serializable
data class CastBallotRequest(
    val voterName: String,
    val rankings: List<Ranking>
)
