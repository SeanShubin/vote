package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * One voter's free-form note attached to one candidate in one election.
 * One note per (election, candidate, voter); a voter may edit their own
 * note at will. Other voters can read but not modify.
 */
@Serializable
data class CandidateNote(
    val electionName: String,
    val candidateName: String,
    val voterName: String,
    val text: String,
    val lastUpdated: Instant,
)
