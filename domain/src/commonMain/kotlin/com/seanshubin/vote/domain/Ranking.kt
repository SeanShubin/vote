package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * One entry in a voter's ballot. [candidateName] holds either a real
 * candidate name or a tier marker name; [kind] disambiguates. The default
 * [RankingKind.CANDIDATE] keeps existing serialized events readable —
 * tier rankings are only produced when the election has tiers configured.
 */
@Serializable
data class Ranking(
    val candidateName: String,
    val rank: Int?,
    val kind: RankingKind = RankingKind.CANDIDATE,
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
