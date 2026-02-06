package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BallotSummary(
    val voterName: String,
    val electionName: String,
    val confirmation: String,
    val whenCast: Instant
)
