package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Ranking.Companion.effectiveRankings
import com.seanshubin.vote.domain.Ranking.Companion.matchOrderToCandidates
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class RevealedBallot(
    val voterName: String,
    val electionName: String,
    val confirmation: String,
    val whenCast: Instant,
    override val rankings: List<Ranking>
) : Ballot {
    fun makeSecret(): SecretBallot = SecretBallot(electionName, confirmation, rankings)

    companion object {
        fun List<RevealedBallot>.matchRankingsOrderToCandidates(candidateNames: List<String>): List<RevealedBallot> =
            map { ballot -> ballot.copy(rankings = ballot.rankings.matchOrderToCandidates(candidateNames)) }

        fun List<RevealedBallot>.effectiveRankings(candidateNames: List<String>): List<RevealedBallot> =
            map { ballot ->
                ballot.copy(
                    rankings = ballot.rankings.effectiveRankings(candidateNames).sortedBy { it.candidateName })
            }
    }
}
