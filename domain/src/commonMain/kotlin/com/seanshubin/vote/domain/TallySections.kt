package com.seanshubin.vote.domain

/**
 * One contiguous chunk of the placings on the Results view. [tierName]
 * is the tier-marker label whose card the [places] sit inside; null
 * means "below the bottom tier" (or the entire list, when no tiers
 * are configured) and renders without a tier wrapper.
 */
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
 *    their Schulze rank is *strictly less than* T's marker rank.
 *    This matches the ballot-side rule (a voter ranks a candidate
 *    *ahead of* a marker to clear it). A tie with the marker means
 *    the pairwise tally couldn't establish strict precedence either
 *    way, so by convention the candidate falls into the next-lower
 *    section. Walks [tiers] top-first (the list is given in declared,
 *    top-first order); each tier claims every still-unclaimed
 *    candidate that strictly beat its marker. Anything left lands
 *    in the trailing no-tier section ([TallySection.tierName] = null).
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
        }
        val rest = displayPlaces.filter { it.candidateName !in claimed }
        if (rest.isNotEmpty()) add(TallySection(null, rest))
    }
}
