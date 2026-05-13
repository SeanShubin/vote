package com.seanshubin.vote.domain

/**
 * Expand a voter's ballot (candidate rankings + per-candidate tier
 * annotations) into the virtual ballot the Schulze pipeline consumes
 * (candidates and tier markers interleaved in one strict order).
 *
 * **Input contract:** every [Ranking] in [rankings] must be
 * [RankingKind.CANDIDATE]. The voter expresses tier-clearing implicitly
 * by setting [Ranking.tier] to the highest-prestige tier the candidate
 * cleared (null = cleared none). Rankings with `rank == null` represent
 * abstention on that candidate and are dropped from the projection — they
 * don't participate in any pairwise contest, so the marker placement
 * doesn't care about them.
 *
 * **Algorithm:** walk [electionTiers] top-to-bottom. For each tier T:
 * - Emit every candidate whose `tier == T`, sorted ascending by [Ranking.rank].
 * - Emit the T marker.
 *
 * After every tier has emitted its marker, append the "cleared no tier"
 * candidates (those with `tier == null`), also sorted by rank.
 *
 * **Why a fresh dense rank index:** the projection assigns ranks 1..N in
 * emission order. The downstream code only ever asks "is A's rank less
 * than B's rank" — exact values don't matter, only relative order — and
 * dense integers keep the comparator simple and the output easy to
 * eyeball in test assertions.
 *
 * **Why this is rename-safe:** the tier marker for "S" is materialized
 * at compute time from [electionTiers], not pulled from the ballot. The
 * ballot only carries the *label* on each candidate, so a rename across
 * the tier list and the per-ranking [Ranking.tier] annotations preserves
 * every voter's intent.
 */
fun projectBallot(
    rankings: List<Ranking>,
    electionTiers: List<String>,
): List<Ranking> {
    require(rankings.all { it.kind == RankingKind.CANDIDATE }) {
        "projectBallot expects only CANDIDATE-kind rankings as input; " +
            "tier markers are materialized by the projection itself"
    }

    val active = rankings.filter { it.rank != null }
    val byTier: Map<String?, List<Ranking>> = active.groupBy { it.tier }

    val emitted = mutableListOf<Ranking>()
    var nextRank = 1
    fun emitCandidate(r: Ranking) {
        emitted += Ranking(
            candidateName = r.candidateName,
            rank = nextRank++,
            kind = RankingKind.CANDIDATE,
            tier = r.tier,
        )
    }
    fun emitMarker(tierName: String) {
        emitted += Ranking(
            candidateName = tierName,
            rank = nextRank++,
            kind = RankingKind.TIER,
            tier = null,
        )
    }

    for (tier in electionTiers) {
        byTier[tier].orEmpty().sortedBy { it.rank }.forEach(::emitCandidate)
        emitMarker(tier)
    }
    byTier[null].orEmpty().sortedBy { it.rank }.forEach(::emitCandidate)

    return emitted
}
