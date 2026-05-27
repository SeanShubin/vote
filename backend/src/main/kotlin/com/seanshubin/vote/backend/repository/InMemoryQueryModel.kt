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
        return data.candidates[electionName.lowercase()]?.size ?: 0
    }

    override fun ballotCount(electionName: String): Int {
        val key = electionName.lowercase()
        return data.ballots.keys.count { it.first == key }
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
        return data.elections[name.lowercase()]?.toElectionSummary()
    }

    override fun listCandidates(electionName: String): List<String> {
        return data.candidates[electionName.lowercase()]?.toList() ?: emptyList()
    }

    override fun listElectionManagers(electionName: String): List<String> {
        return data.electionManagers[electionName.lowercase()]
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()
    }

    override fun candidateBallotCounts(electionName: String): Map<String, Int> {
        val electionKey = electionName.lowercase()
        val candidateSet = data.candidates[electionKey] ?: return emptyMap()
        // Start every candidate at zero so the map's key set always matches
        // the candidate list — callers (the editor UI) want a count for each
        // candidate, even ones with no ballots.
        val counts = candidateSet.associateWith { 0 }.toMutableMap()
        // Candidate names in stored ballots are canonicalized at projection
        // time, so a direct lookup matches; the case-insensitive fallback
        // catches any historical drift.
        val canonicalByLower = candidateSet.associateBy { it.lowercase() }
        data.ballots
            .filter { it.key.first == electionKey }
            .values
            .forEach { ballot ->
                ballot.rankings
                    .map { it.candidateName }
                    .toSet()
                    .forEach { name ->
                        val canonical = canonicalByLower[name.lowercase()] ?: return@forEach
                        counts[canonical] = counts.getValue(canonical) + 1
                    }
            }
        return counts
    }

    override fun listTiers(electionName: String): List<String> {
        return data.tiers[electionName.lowercase()] ?: emptyList()
    }

    override fun listRankings(voterName: String, electionName: String): List<Ranking> {
        val ballot = data.ballots[electionName.lowercase() to voterName.lowercase()]
        return ballot?.rankings ?: emptyList()
    }

    override fun listRankings(electionName: String): List<VoterElectionCandidateRank> {
        val key = electionName.lowercase()
        return data.ballots
            .filter { it.key.first == key }
            .values
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
        return data.ballots[electionName.lowercase() to voterName.lowercase()]?.toBallotSummary()
    }

    override fun listBallots(electionName: String): List<Ballot.Identified> {
        val key = electionName.lowercase()
        return data.ballots
            .filter { it.key.first == key }
            .values
            .map { it.toIdentifiedBallot() }
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

    override fun listCandidateNotes(electionName: String, candidateName: String): List<CandidateNote> {
        val electionKey = electionName.lowercase()
        val candidateKey = candidateName.lowercase()
        return data.candidateNotes
            .filter { (k, _) -> k.first == electionKey && k.second == candidateKey }
            .values
            .sortedByDescending { it.lastUpdated }
            .map { it.toCandidateNote() }
    }

    override fun listCandidateNotesByVoter(voterName: String): List<CandidateNote> {
        val key = voterName.lowercase()
        return data.candidateNotes
            .filter { (k, _) -> k.third == key }
            .values
            .map { it.toCandidateNote() }
    }

    override fun listCandidateNotesByElection(electionName: String): List<CandidateNote> {
        val key = electionName.lowercase()
        return data.candidateNotes
            .filter { (k, _) -> k.first == key }
            .values
            .map { it.toCandidateNote() }
    }
}
