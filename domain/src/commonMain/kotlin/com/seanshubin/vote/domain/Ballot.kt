package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy with subtypes nested inside on purpose: keeps the
 * sealed-permits cycle (parent ↔ children) folded under one code unit
 * via the analyzer's $-truncation rule.
 *
 * [Identified] carries the voter name; [Anonymous] is the redacted
 * projection for callers that lack VIEW_SECRETS authority on a secret-side
 * tally. Wire format is preserved by @SerialName — JSON sees
 * "IdentifiedBallot" and "AnonymousBallot" regardless of Kotlin nesting.
 */
@Serializable
sealed interface Ballot {
    val rankings: List<Ranking>

    @Serializable
    @SerialName("IdentifiedBallot")
    data class Identified(
        val voterName: String,
        val electionName: String,
        val confirmation: String,
        val whenCast: Instant,
        override val rankings: List<Ranking>
    ) : Ballot {
        fun makeAnonymous(): Anonymous = Anonymous(electionName, confirmation, rankings)
    }

    @Serializable
    @SerialName("AnonymousBallot")
    data class Anonymous(
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
