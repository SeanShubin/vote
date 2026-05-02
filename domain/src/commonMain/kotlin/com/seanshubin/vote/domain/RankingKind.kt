package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Tag for [Ranking] entries: a real candidate vs a tier marker. Tier
 * markers are virtual candidates the voter ranks alongside real ones —
 * placing a candidate ahead of a tier marker on the ballot means "this
 * candidate clears that tier." A candidate's tier in the aggregate result
 * is the highest tier they collectively cleared. Distinguishing tier
 * markers from real candidates lets the UI render them differently and
 * lets validation enforce that markers stay in their declared order on
 * every ballot.
 */
@Serializable
enum class RankingKind {
    CANDIDATE,
    TIER,
}
