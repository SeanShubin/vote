package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.domain.*
import kotlinx.datetime.Instant

/**
 * Canonical-key invariant: every user-supplied identifier used as a map key
 * (user name, election name, voter name) is lowercased. Names in this app
 * are case-insensitive — only passwords would be case-sensitive, and there
 * are no user passwords (Discord OAuth only). Display case is preserved in
 * the value's name/electionName/voterName attribute. Reads and writes go
 * through .lowercase() at the boundary; callers must not assume the raw key
 * is a presentable name.
 *
 * For [candidates] and [tiers] the *value* collections carry display-case
 * names directly; equality and dedup against them use case-insensitive
 * comparison inside the command/query model.
 */
class InMemoryData {
    val users = mutableMapOf<String, UserData>()
    val elections = mutableMapOf<String, ElectionData>()
    val candidates = mutableMapOf<String, MutableSet<String>>()
    val electionManagers = mutableMapOf<String, MutableSet<String>>()
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

        fun toIdentifiedBallot() = Ballot.Identified(
            voterName = voterName,
            electionName = electionName,
            rankings = rankings,
            confirmation = confirmation,
            whenCast = whenCast
        )
    }
}
