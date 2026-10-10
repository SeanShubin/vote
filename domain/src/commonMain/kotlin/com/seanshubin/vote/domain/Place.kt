package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class Place(val rank: Int, val candidateName: String) {
    override fun toString(): String = "$rank $candidateName"

    companion object {
        /**
         * Within a rank, order candidates by how many ballots they appear
         * on, most first.
         *
         * A tie means the pairwise process established no order between
         * these candidates, so something has to decide how they are listed.
         * Ranked Pairs falls back to alphabetical, which is stable but
         * arbitrary — it puts a candidate one voter mentioned above one that
         * half the electorate ranked, purely because of its name. Breadth of
         * support is the more informative answer to "why is this one first",
         * and it is already the thing a reader of a tied group wants to know.
         *
         * This changes presentation only. Ranks are untouched, so a tie is
         * still reported as a tie; this is the order within it.
         *
         * [appearances] counts ballots, not points: a name is worth one
         * whether it was ranked first or last. Alphabetical remains the final
         * fallback so two candidates on equally many ballots stay in a stable
         * order across runs. A name absent from [appearances] counts as zero,
         * which is how tier markers — virtual candidates that appear on no
         * ballot — sort to the end of their group.
         */
        fun List<Place>.orderTiesByAppearances(appearances: Map<String, Int>): List<Place> =
            sortedWith(
                compareBy<Place> { it.rank }
                    .thenByDescending { appearances[it.candidateName] ?: 0 }
                    .thenBy { it.candidateName },
            )

        /**
         * How many ballots each candidate appears on, for
         * [orderTiesByAppearances]. Counted per ballot rather than per
         * ranking, so a ballot contributes at most one to a candidate.
         */
        fun appearanceCounts(ballots: List<Ballot>): Map<String, Int> =
            ballots
                .flatMap { ballot ->
                    ballot.rankings
                        .filter { it.rank != null }
                        .map { it.candidateName }
                        .distinct()
                }
                .groupingBy { it }
                .eachCount()

        fun List<Place>.adjustForTies(): List<Place> {
            val grouped: List<List<Place>> = this.groupBy { it.rank }
                .toList()
                .sortedBy { (rank, _) -> rank }
                .map { (_, places) -> places }
            return grouped.flatMapIndexed { index, places ->
                val previousDuplicates = grouped.take(index).sumOf { it.size - 1 }
                places.map { it.copy(rank = index + previousDuplicates + 1) }
            }
        }
    }
}
