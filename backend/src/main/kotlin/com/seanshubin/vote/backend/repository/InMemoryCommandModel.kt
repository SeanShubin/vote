package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant

class InMemoryCommandModel(private val data: InMemoryData) : CommandModel {
    override fun setLastSynced(lastSynced: Long) {
        data.lastSynced = lastSynced
    }

    override fun initializeLastSynced(lastSynced: Long) {
        if (data.lastSynced == null) {
            data.lastSynced = lastSynced
        }
    }

    override fun setRole(authority: String, userName: String, role: Role) {
        val key = userName.lowercase()
        val user = data.users[key] ?: error("User not found: $userName")
        data.users[key] = user.copy(role = role)
    }

    override fun removeUser(authority: String, userName: String) {
        data.users.remove(userName.lowercase())
        // Cascade: drop any ballots this user cast (matches MySQL FK CASCADE on
        // ballots.voter_name → users(name)). Without this, removing a voter would
        // leave their ballot rows pointing at a non-existent user.
        data.ballots.entries
            .filter { (_, ballot) -> ballot.voterName.equals(userName, ignoreCase = true) }
            .map { it.key }
            .forEach { data.ballots.remove(it) }
    }

    override fun addElection(authority: String, owner: String, electionName: String, description: String) {
        data.elections[electionName] = InMemoryData.ElectionData(
            ownerName = owner,
            electionName = electionName,
            description = description,
        )
        data.candidates[electionName] = mutableSetOf()
    }

    override fun setElectionDescription(authority: String, electionName: String, description: String) {
        val election = data.elections[electionName] ?: error("Election not found: $electionName")
        data.elections[electionName] = election.copy(description = description)
    }

    override fun deleteElection(authority: String, electionName: String) {
        data.elections.remove(electionName)
        data.candidates.remove(electionName)
        data.tiers.remove(electionName)
        data.ballots.keys.removeIf { (election, _) -> election == electionName }
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        val candidates = data.candidates.getOrPut(electionName) { mutableSetOf() }
        candidates.addAll(candidateNames)
    }

    override fun setTiers(authority: String, electionName: String, tierNames: List<String>) {
        if (tierNames.isEmpty()) {
            data.tiers.remove(electionName)
        } else {
            data.tiers[electionName] = tierNames.toList()
        }
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        val candidates = data.candidates[electionName] ?: return
        candidates.removeAll(candidateNames.toSet())
        // Cascade: strip the removed candidate names from any existing ballot's
        // ranking list in this election. Without this, ballots would carry
        // ghost rankings for candidates that no longer exist.
        val removed = candidateNames.toSet()
        data.ballots.entries
            .filter { (_, ballot) -> ballot.electionName == electionName }
            .forEach { (key, ballot) ->
                val filtered = ballot.rankings.filter { it.candidateName !in removed }
                if (filtered.size != ballot.rankings.size) {
                    data.ballots[key] = ballot.copy(rankings = filtered)
                }
            }
    }

    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        now: Instant
    ) {
        val key = electionName to voterName.lowercase()
        data.ballots[key] = InMemoryData.BallotData(
            voterName = voterName,
            electionName = electionName,
            rankings = rankings,
            confirmation = confirmation,
            whenCast = now
        )
    }

    override fun setRankings(
        authority: String,
        confirmation: String,
        electionName: String,
        rankings: List<Ranking>
    ) {
        val entry = data.ballots.entries.find { it.value.confirmation == confirmation }
            ?: error("Ballot not found with confirmation: $confirmation")
        val ballot = entry.value
        data.ballots[entry.key] = ballot.copy(rankings = rankings)
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        val entry = data.ballots.entries.find { it.value.confirmation == confirmation }
            ?: error("Ballot not found with confirmation: $confirmation")
        val ballot = entry.value
        data.ballots[entry.key] = ballot.copy(whenCast = now)
    }

    override fun deleteBallot(authority: String, voterName: String, electionName: String) {
        data.ballots.remove(electionName to voterName.lowercase())
    }

    override fun setUserName(authority: String, oldUserName: String, newUserName: String) {
        val oldKey = oldUserName.lowercase()
        val newKey = newUserName.lowercase()
        val user = data.users[oldKey] ?: error("User not found: $oldUserName")
        if (oldKey != newKey) {
            data.users.remove(oldKey)
        }
        data.users[newKey] = user.copy(name = newUserName)

        // Update election ownership — match the previous canonical name case-insensitively
        // since stored values may differ in case from what was passed in.
        data.elections.values
            .filter { it.ownerName.equals(oldUserName, ignoreCase = true) }
            .forEach { election ->
                data.elections[election.electionName] = election.copy(ownerName = newUserName)
            }

        // Update ballots
        val ballotsToUpdate = data.ballots.entries
            .filter { it.value.voterName.equals(oldUserName, ignoreCase = true) }
            .toList()
        ballotsToUpdate.forEach { (key, ballot) ->
            data.ballots.remove(key)
            val newBallotKey = key.first to newKey
            data.ballots[newBallotKey] = ballot.copy(voterName = newUserName)
        }
    }

    override fun createUserViaDiscord(
        authority: String,
        userName: String,
        discordId: String,
        discordDisplayName: String,
        role: Role,
    ) {
        data.users[userName.lowercase()] = InMemoryData.UserData(
            name = userName,
            role = role,
            discordId = discordId,
            discordDisplayName = discordDisplayName,
        )
    }

    override fun linkDiscordCredential(
        authority: String,
        userName: String,
        discordId: String,
        discordDisplayName: String,
    ) {
        val key = userName.lowercase()
        val user = data.users[key] ?: error("User not found: $userName")
        data.users[key] = user.copy(
            discordId = discordId,
            discordDisplayName = discordDisplayName,
        )
    }
}
