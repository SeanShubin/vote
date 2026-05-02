package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Tag for [Ranking] entries: a real candidate vs a tier marker. When an
 * election has tier names configured, every ballot ranks both candidates
 * AND tier markers in one ordered list, so the algorithm can decide which
 * tier each candidate beats. Distinguishing them lets the UI render tier
 * markers differently and lets validation enforce that tier markers stay
 * in their declared order on every ballot.
 */
@Serializable
enum class RankingKind {
    CANDIDATE,
    TIER,
}
