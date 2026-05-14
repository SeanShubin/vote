package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.domain.*
import kotlinx.datetime.Instant

class InMemoryData {
    val users = mutableMapOf<String, UserData>()
    val elections = mutableMapOf<String, ElectionData>()
    val candidates = mutableMapOf<String, MutableSet<String>>()
    // Per-election ordered tier names. The list is the source of truth for
    // tier ordering; the InMemory store mirrors what MySQL stores in a
    // separate table and what DynamoDB stores as an attribute on the
    // election METADATA item.
    val tiers = mutableMapOf<String, List<String>>()
    val ballots = mutableMapOf<Pair<String, String>, BallotData>()
    var lastSynced: Long? = null

    data class UserData(
        val name: String,
        val role: Role,
        val discordId: String = "",
        val discordDisplayName: String = "",
    ) {
        fun toUser() = User(name, role, discordId, discordDisplayName)
    }

    data class ElectionData(
        val ownerName: String,
        val electionName: String,
        val description: String = "",
    ) {
        fun toElectionSummary() = ElectionSummary(
            ownerName = ownerName,
            electionName = electionName,
            description = description,
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

        fun toRevealedBallot() = Ballot.Revealed(
            voterName = voterName,
            electionName = electionName,
            rankings = rankings,
            confirmation = confirmation,
            whenCast = whenCast
        )
    }
}
