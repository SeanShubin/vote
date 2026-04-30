package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Ranking.Companion.effectiveRankings
import com.seanshubin.vote.domain.Ranking.Companion.matchOrderToCandidates
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy with subtypes nested inside on purpose: keeps the
 * sealed-permits cycle (parent ↔ children) folded under one code unit
 * via the analyzer's $-truncation rule.
 *
 * Wire format is preserved by @SerialName — JSON sees "RevealedBallot"
 * and "SecretBallot" regardless of Kotlin nesting.
 */
@Serializable
sealed interface Ballot {
    val rankings: List<Ranking>

    @Serializable
    @SerialName("RevealedBallot")
    data class Revealed(
        val voterName: String,
        val electionName: String,
        val confirmation: String,
        val whenCast: Instant,
        override val rankings: List<Ranking>
    ) : Ballot {
        fun makeSecret(): Secret = Secret(electionName, confirmation, rankings)

        companion object {
            fun List<Revealed>.matchRankingsOrderToCandidates(candidateNames: List<String>): List<Revealed> =
                map { ballot -> ballot.copy(rankings = ballot.rankings.matchOrderToCandidates(candidateNames)) }

            fun List<Revealed>.effectiveRankings(candidateNames: List<String>): List<Revealed> =
                map { ballot ->
                    ballot.copy(
                        rankings = ballot.rankings.effectiveRankings(candidateNames).sortedBy { it.candidateName })
                }
        }
    }

    @Serializable
    @SerialName("SecretBallot")
    data class Secret(
        val electionName: String,
        val confirmation: String,
        override val rankings: List<Ranking>
    ) : Ballot

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
