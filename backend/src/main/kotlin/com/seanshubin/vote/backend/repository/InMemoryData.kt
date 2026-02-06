package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.domain.*
import kotlinx.datetime.Instant

class InMemoryData {
    // Users
    val users = mutableMapOf<String, UserData>()

    // Elections
    val elections = mutableMapOf<String, ElectionData>()

    // Candidates per election
    val candidates = mutableMapOf<String, MutableSet<String>>()

    // Eligible voters per election
    val eligibleVoters = mutableMapOf<String, MutableSet<String>>()

    // Ballots: (electionName, voterName) -> BallotData
    val ballots = mutableMapOf<Pair<String, String>, BallotData>()

    // Sync tracking
    var lastSynced: Long? = null

    data class UserData(
        val name: String,
        val email: String,
        val salt: String,
        val hash: String,
        val role: Role
    ) {
        fun toUser() = User(name, email, salt, hash, role)
    }

    data class ElectionData(
        val ownerName: String,
        val electionName: String,
        val secretBallot: Boolean? = null,
        val noVotingBefore: Instant? = null,
        val noVotingAfter: Instant? = null,
        val allowEdit: Boolean? = null,
        val allowVote: Boolean? = null
    ) {
        fun toElectionSummary() = ElectionSummary(
            ownerName = ownerName,
            electionName = electionName,
            secretBallot = secretBallot ?: true,
            noVotingBefore = noVotingBefore,
            noVotingAfter = noVotingAfter,
            allowEdit = allowEdit ?: true,
            allowVote = allowVote ?: false
        )
    }

    data class BallotData(
        val voterName: String,
        val electionName: String,
        val rankings: List<Ranking>,
        val confirmation: String,
        val whenCast: Instant
    ) {
        fun toBallotSummary() = BallotSummary(
            voterName = voterName,
            electionName = electionName,
            confirmation = confirmation,
            whenCast = whenCast
        )

        fun toRevealedBallot() = RevealedBallot(
            voterName = voterName,
            electionName = electionName,
            rankings = rankings,
            confirmation = confirmation,
            whenCast = whenCast
        )
    }
}
