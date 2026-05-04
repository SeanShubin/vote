package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * One contiguous chunk of the placings on the Results view. [tierName]
 * non-null means a tier card with that label. [tierName] null means a
 * naked row list — used for both candidates tied with a tier boundary
 * (the section sits between two tier cards) and candidates that
 * cleared no tier (the trailing section, or the entire list when no
 * tiers are configured). The renderer doesn't need to distinguish the
 * two null cases: position in the section list communicates which is
 * which, and the contents are formatted identically either way.
 */
@Serializable
data class TallySection(val tierName: String?, val places: List<Place>)

/**
 * Splits a Schulze-ranked place list into tier sections for display.
 * Two responsibilities are bundled here because they consume the same
 * input and have to agree on which entries are tier markers:
 *
 * 1. **Renumber for display.** Tier markers participate in the
 *    Schulze tally as virtual candidates, so [places] contains both
 *    real candidates and tier markers and a marker's position would
 *    otherwise eat a rank number ("Alice 1st, Bob 3rd" with a marker
 *    sitting between them). Markers are stripped and the survivors
 *    are renumbered 1..N while preserving Schulze-induced ties — two
 *    candidates the tally placed at the same Schulze rank still tie
 *    in the display.
 *
 * 2. **Assign tier sections.** A candidate **clears** tier T iff
 *    their Schulze rank is *strictly less than* T's marker rank,
 *    mirroring the ballot-side rule (a voter ranks a candidate
 *    *ahead of* a marker to clear it). A candidate **ties** with T
 *    iff their Schulze rank *equals* T's — the pairwise tally
 *    couldn't establish strict precedence either way. Cleared
 *    candidates render inside that tier's card; tied candidates
 *    render in a naked row list immediately after the card,
 *    visibly between the tier above and the tier below. Walks
 *    [tiers] top-first; each tier claims every still-unclaimed
 *    candidate cleared or tied with it. Anyone unclaimed at the end
 *    cleared no tier and lands in a trailing naked row list.
 *
 * If the election has no tiers, every candidate lands in a single
 * no-tier section, which the renderer draws as a plain row list.
 */
fun tallySections(places: List<Place>, tiers: List<String>): List<TallySection> {
    val tierSet = tiers.toSet()
    val candidatesOnly = places.filter { it.candidateName !in tierSet }
    val tierMarkerRank: Map<String, Int> = places
        .filter { it.candidateName in tierSet }
        .associate { it.candidateName to it.rank }
    val schulzeRankByName: Map<String, Int> = candidatesOnly
        .associate { it.candidateName to it.rank }

    val displayPlaces: List<Place> = run {
        val displayRankByName = mutableMapOf<String, Int>()
        var assigned = 0
        candidatesOnly.distinctBy { it.rank }.sortedBy { it.rank }.forEach { sample ->
            val tied = candidatesOnly.filter { it.rank == sample.rank }
            tied.forEach { displayRankByName[it.candidateName] = assigned + 1 }
            assigned += tied.size
        }
        candidatesOnly.map { Place(displayRankByName.getValue(it.candidateName), it.candidateName) }
    }

    return buildList {
        val claimed = mutableSetOf<String>()
        tiers.forEach { tierName ->
            val tierRank = tierMarkerRank[tierName] ?: return@forEach
            val cleared = displayPlaces.filter {
                it.candidateName !in claimed &&
                    schulzeRankByName.getValue(it.candidateName) < tierRank
            }
            add(TallySection(tierName, cleared))
            claimed.addAll(cleared.map { it.candidateName })

            val tiedAtBoundary = displayPlaces.filter {
                it.candidateName !in claimed &&
                    schulzeRankByName.getValue(it.candidateName) == tierRank
            }
            if (tiedAtBoundary.isNotEmpty()) {
                add(TallySection(null, tiedAtBoundary))
                claimed.addAll(tiedAtBoundary.map { it.candidateName })
            }
        }
        val rest = displayPlaces.filter { it.candidateName !in claimed }
        if (rest.isNotEmpty()) add(TallySection(null, rest))
    }
}
