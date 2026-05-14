package com.seanshubin.vote.domain

/**
 * Plain-text rendering of a ballot, suitable for paste into chat / email
 * and for offline download. Plain mode is a flat dashed list of candidates
 * in rank order. Tier mode nests each tier's candidates one indent under
 * the tier name, using the same "candidate clears tier T iff they sit
 * ahead of T's marker" interpretation the on-screen view uses. Empty
 * tiers still appear as a heading so the reader can see the full ladder.
 *
 * [rankings] must be in display order (smallest rank first). Entries with
 * [Ranking.kind] of [RankingKind.TIER] are rendered as tier headings;
 * [RankingKind.CANDIDATE] entries are rendered under whichever tier
 * heading they precede (or as a flat list when no tier markers exist).
 */
fun buildBallotText(
    electionName: String,
    userName: String?,
    rankings: List<Ranking>,
): String {
    val ownerLine = if (userName.isNullOrBlank()) "Your Rankings" else "$userName's Rankings"
    val ordered = rankings.sortedBy { it.rank ?: Int.MAX_VALUE }
    val hasTiers = ordered.any { it.kind == RankingKind.TIER }
    val lines = mutableListOf<String>()
    lines += electionName
    lines += ownerLine
    if (!hasTiers) {
        ordered.filter { it.kind == RankingKind.CANDIDATE }.forEach { r ->
            lines += "- ${r.candidateName}"
        }
    } else {
        val pending = mutableListOf<String>()
        ordered.forEach { r ->
            when (r.kind) {
                RankingKind.CANDIDATE -> pending += r.candidateName
                RankingKind.TIER -> {
                    lines += "- ${r.candidateName}"
                    pending.forEach { lines += "  - $it" }
                    pending.clear()
                }
            }
        }
    }
    return lines.joinToString("\n")
}
