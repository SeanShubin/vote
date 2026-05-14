package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.*

class InMemoryQueryModel(private val data: InMemoryData) : QueryModel {
    override fun findUserByName(name: String): User {
        return data.users[name.lowercase()]?.toUser() ?: error("User not found: $name")
    }

    override fun searchUserByName(name: String): User? {
        return data.users[name.lowercase()]?.toUser()
    }

    override fun searchUserByDiscordId(discordId: String): User? {
        // Blank discordId never matches anyone — defensive only; every user
        // is created via Discord OAuth and so always has a non-empty id.
        if (discordId.isEmpty()) return null
        return data.users.values.find { it.discordId == discordId }?.toUser()
    }

    override fun userCount(): Int = data.users.size

    override fun electionCount(): Int = data.elections.size

    override fun candidateCount(electionName: String): Int {
        return data.candidates[electionName]?.size ?: 0
    }

    override fun ballotCount(electionName: String): Int {
        return data.ballots.values.count { it.electionName == electionName }
    }

    override fun tableCount(): Int {
        // users, elections, candidates, ballots
        return 4
    }

    override fun listUsers(): List<User> {
        return data.users.values.map { it.toUser() }
    }

    override fun listElections(): List<ElectionSummary> {
        return data.elections.values.map { it.toElectionSummary() }
    }

    override fun roleHasPermission(role: Role, permission: Permission): Boolean {
        return when (permission) {
            Permission.VIEW_APPLICATION -> role >= Role.OBSERVER
            Permission.VOTE -> role >= Role.VOTER
            Permission.USE_APPLICATION -> role >= Role.USER
            Permission.MANAGE_USERS -> role >= Role.ADMIN
            Permission.VIEW_SECRETS -> role >= Role.AUDITOR
            Permission.TRANSFER_OWNER -> role == Role.OWNER
        }
    }

    override fun lastSynced(): Long? = data.lastSynced

    override fun searchElectionByName(name: String): ElectionSummary? {
        return data.elections[name]?.toElectionSummary()
    }

    override fun listCandidates(electionName: String): List<String> {
        return data.candidates[electionName]?.toList() ?: emptyList()
    }

    override fun listTiers(electionName: String): List<String> {
        return data.tiers[electionName] ?: emptyList()
    }

    override fun listRankings(voterName: String, electionName: String): List<Ranking> {
        val ballot = data.ballots[electionName to voterName.lowercase()]
        return ballot?.rankings ?: emptyList()
    }

    override fun listRankings(electionName: String): List<VoterElectionCandidateRank> {
        return data.ballots.values
            .filter { it.electionName == electionName }
            .flatMap { ballot ->
                ballot.rankings.mapNotNull { ranking ->
                    ranking.rank?.let { rank ->
                        VoterElectionCandidateRank(
                            voter = ballot.voterName,
                            election = ballot.electionName,
                            candidate = ranking.candidateName,
                            rank = rank
                        )
                    }
                }
            }
    }

    override fun searchBallot(voterName: String, electionName: String): BallotSummary? {
        return data.ballots[electionName to voterName.lowercase()]?.toBallotSummary()
    }

    override fun listBallots(electionName: String): List<Ballot.Revealed> {
        return data.ballots.values
            .filter { it.electionName == electionName }
            .map { it.toRevealedBallot() }
    }

    override fun listUserNames(): List<String> {
        return data.users.values.map { it.name }
    }

    override fun listPermissions(role: Role): List<Permission> {
        return Permission.entries.filter { roleHasPermission(role, it) }
    }

    override fun electionsOwnedCount(userName: String): Int =
        data.elections.values.count { it.ownerName.equals(userName, ignoreCase = true) }

    override fun ballotsCastCount(userName: String): Int =
        data.ballots.values.count { it.voterName.equals(userName, ignoreCase = true) }
}
