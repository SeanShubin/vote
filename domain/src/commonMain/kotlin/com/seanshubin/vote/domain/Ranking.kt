package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * One entry in a voter's ballot.
 *
 * **Storage form** (what voters cast and what the event log records):
 * [candidateName] is always a real candidate, [kind] is always
 * [RankingKind.CANDIDATE], and [tier] is the highest-prestige tier this
 * candidate cleared (null = cleared none, sits below every tier marker).
 * The voter never explicitly ranks tier markers — they pick a tier per
 * candidate and the projection step materializes the markers.
 *
 * **Projected form** (what the pairwise tally pipeline consumes): the same
 * candidate rankings plus synthetic [kind] = [RankingKind.TIER] entries
 * inserted at the right cut points by [projectBallot]. The tier annotation
 * on candidate rankings is what tells the projection where each marker goes.
 *
 * [tier] and [kind] both default to "plain candidate, no tier" so existing
 * serialized events without these fields still deserialize.
 */
@Serializable
data class Ranking(
    val candidateName: String,
    val rank: Int?,
    val kind: RankingKind = RankingKind.CANDIDATE,
    val tier: String? = null,
) {
    companion object {
        /**
         * A ballot expresses a pairwise preference for `a` over `b` only when
         * BOTH candidates appear on the ballot with a non-null rank, and `a`'s
         * rank is strictly lower (better) than `b`'s. If either candidate is
         * missing from the ballot or has `rank=null`, this ballot abstains
         * from the (a, b) contest — neither side gets a vote.
         */
        fun List<Ranking>.prefers(a: String, b: String): Boolean {
            val rankA = rankOf(a) ?: return false
            val rankB = rankOf(b) ?: return false
            return rankA < rankB
        }

        private fun List<Ranking>.rankOf(candidateName: String): Int? =
            find { ranking -> ranking.candidateName == candidateName }?.rank

        object RankingListComparator : Comparator<List<Ranking>> {
            override fun compare(firstRankingList: List<Ranking>, secondRankingList: List<Ranking>): Int {
                val firstRankList = firstRankingList.mapNotNull { it.rank }
                val secondRankList = secondRankingList.mapNotNull { it.rank }
                return RankListComparator.compare(firstRankList, secondRankList)
            }
        }

        object RankListComparator : Comparator<List<Int>> {
            // Lexicographic over the shared prefix; ties broken by length
            // (shorter ballot first). Ballots may have different lengths now
            // that we no longer pad them with synthetic last-place entries.
            override fun compare(firstList: List<Int>, secondList: List<Int>): Int {
                val sharedSize = min(firstList.size, secondList.size)
                for (index in 0 until sharedSize) {
                    val cmp = RankComparator.compare(firstList[index], secondList[index])
                    if (cmp != 0) return cmp
                }
                return firstList.size.compareTo(secondList.size)
            }
        }

        object RankComparator : Comparator<Int> {
            override fun compare(first: Int, second: Int): Int = first.compareTo(second)
        }
    }
}
