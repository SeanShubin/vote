package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.random.Random

@Serializable
data class Ranking(val candidateName: String, val rank: Int?) {
    companion object {
        fun List<Ranking>.prefers(a: String, b: String): Boolean =
            rankingFor(a) < rankingFor(b)

        private fun List<Ranking>.rankingFor(candidateName: String): Int =
            find { ranking -> ranking.candidateName == candidateName }?.rank ?: Int.MAX_VALUE

        fun List<Ranking>.listToString() =
            joinToString(" ") { (candidateName, rank) -> "$rank $candidateName" }

        fun List<Ranking>.voterBiasedOrdering(random: Random): List<Ranking> {
            val rankAscending = Comparator<Ranking> { o1, o2 ->
                val rank1 = o1?.rank ?: Int.MAX_VALUE
                val rank2 = o2?.rank ?: Int.MAX_VALUE
                rank1.compareTo(rank2)
            }
            val grouped = groupBy { it.rank }
            val groupedValues = grouped.values
            val shuffled = groupedValues.flatMap { it.shuffled(random) }
            val sorted = shuffled.sortedWith(rankAscending)
            return sorted
        }

        fun List<Ranking>.addMissingCandidates(allCandidates: List<String>): List<Ranking> {
            val existingCandidates = this.map { it.candidateName }
            val isMissing = { candidate: String -> !existingCandidates.contains(candidate) }
            val missingCandidates = allCandidates.filter(isMissing)
            val newRankings = missingCandidates.map { Ranking(it, null) }
            return this + newRankings
        }

        fun List<Ranking>.normalizeRankingsReplaceNulls(): List<Ranking> {
            val distinctOrderedRanks = this.mapNotNull { it.rank }.distinct().sorted()
            val normalized = (1..distinctOrderedRanks.size)
            val newRankMap = distinctOrderedRanks.zip(normalized).toMap()
            val lastRank = distinctOrderedRanks.size + 1
            val result = map { (name, rank) ->
                val newRank = newRankMap[rank] ?: lastRank
                Ranking(name, newRank)
            }
            return result
        }

        fun List<Ranking>.normalizeRankingsKeepNulls(): List<Ranking> {
            val distinctOrderedRanks = this.mapNotNull { it.rank }.distinct().sorted()
            val normalized = (1..distinctOrderedRanks.size)
            val newRankMap = distinctOrderedRanks.zip(normalized).toMap()
            val result = map { (name, rank) ->
                val newRank = newRankMap[rank]
                Ranking(name, newRank)
            }
            return result
        }

        fun List<Ranking>.effectiveRankings(candidateNames: List<String>): List<Ranking> =
            addMissingCandidates(candidateNames).normalizeRankingsReplaceNulls()

        fun List<Ranking>.matchOrderToCandidates(candidateNames: List<String>): List<Ranking> {
            val byCandidate = this.associateBy { it.candidateName }
            return candidateNames.map { byCandidate.getValue(it) }
        }

        object RankingListComparator : Comparator<List<Ranking>> {
            override fun compare(firstRankingList: List<Ranking>, secondRankingList: List<Ranking>): Int {
                val firstRankList = firstRankingList.mapNotNull { it.rank }
                val secondRankList = secondRankingList.mapNotNull { it.rank }
                return RankListComparator.compare(firstRankList, secondRankList)
            }
        }

        object RankListComparator : Comparator<List<Int>> {
            override fun compare(firstList: List<Int>, secondList: List<Int>): Int {
                val maxSize = max(firstList.size, secondList.size)
                var compareResult = 0
                var index = 0
                while (index < maxSize) {
                    val firstValue = firstList[index]
                    val secondValue = secondList[index]
                    compareResult = RankComparator.compare(firstValue, secondValue)
                    if (compareResult != 0) break
                    index++
                }
                return compareResult
            }
        }

        object RankComparator : Comparator<Int> {
            override fun compare(first: Int, second: Int): Int = first.compareTo(second)
        }
    }
}
