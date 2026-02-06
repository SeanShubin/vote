package com.seanshubin.vote.domain

interface Ballot {
    val rankings: List<Ranking>

    companion object {
        object BallotComparator : Comparator<Ballot> {
            override fun compare(first: Ballot, second: Ballot): Int =
                Ranking.Companion.RankingListComparator.compare(
                    first.rankings,
                    second.rankings
                )
        }
    }
}
