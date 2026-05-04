package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * The wire-level tally returned to clients. Wraps the raw Schulze [tally]
 * with the tier list so consumers can identify tier markers without
 * having to recover that information from ballot kind tags. [sections]
 * is the placings-page grouping precomputed once on the server.
 *
 * Tier markers participate in the Schulze run as virtual candidates —
 * that's still how candidate-vs-candidate orderings can be influenced
 * by voters' positions against thresholds. The detail pages render
 * tier markers visibly but styled differently from real candidates;
 * [isTier] is the single authoritative classifier.
 */
@Serializable
data class ElectionTally(
    val tally: Tally,
    val tiers: List<String>,
    val sections: List<TallySection>,
) {
    fun isTier(name: String): Boolean = name in tiers
}
