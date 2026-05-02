package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Returned by GET /election/{name}. The header on the SPA's election detail
 * page binds to this — owner + the count of candidates and ballots cast.
 */
@Serializable
data class ElectionDetail(
    val ownerName: String,
    val electionName: String,
    val candidateCount: Int,
    val ballotCount: Int,
    val description: String = "",
    /**
     * Optional ordered list of tier names. When non-empty, ballots include
     * tier markers in this exact order alongside candidates, and the tally
     * places candidates relative to those markers. Empty list = no tiers,
     * the app behaves as before tier support existed.
     *
     * Tier names cannot be modified while [ballotCount] > 0 — changing a
     * tier name out from under voters who already cast against it would
     * silently invalidate their meaning.
     */
    val tiers: List<String> = emptyList(),
)
