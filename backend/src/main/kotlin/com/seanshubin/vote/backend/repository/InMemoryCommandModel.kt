package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.domain.ElectionUpdates
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

    override fun createUser(
        authority: String,
        userName: String,
        email: String,
        salt: String,
        hash: String,
        role: Role
    ) {
        data.users[userName] = InMemoryData.UserData(userName, email, salt, hash, role)
    }

    override fun setRole(authority: String, userName: String, role: Role) {
        val user = data.users[userName] ?: error("User not found: $userName")
        data.users[userName] = user.copy(role = role)
    }

    override fun removeUser(authority: String, userName: String) {
        data.users.remove(userName)
        // Remove user from all eligible voter lists
        data.eligibleVoters.values.forEach { it.remove(userName) }
    }

    override fun addElection(authority: String, owner: String, electionName: String) {
        data.elections[electionName] = InMemoryData.ElectionData(
            ownerName = owner,
            electionName = electionName
        )
        data.candidates[electionName] = mutableSetOf()
        data.eligibleVoters[electionName] = mutableSetOf()
    }

    override fun updateElection(authority: String, electionName: String, updates: ElectionUpdates) {
        val election = data.elections[electionName] ?: error("Election not found: $electionName")
        data.elections[electionName] = InMemoryData.ElectionData(
            ownerName = election.ownerName,
            electionName = election.electionName,
            secretBallot = updates.secretBallot ?: election.secretBallot,
            noVotingBefore = updates.noVotingBefore ?: election.noVotingBefore,
            noVotingAfter = updates.noVotingAfter ?: election.noVotingAfter,
            allowEdit = updates.allowEdit ?: election.allowEdit,
            allowVote = updates.allowVote ?: election.allowVote
        )
    }

    override fun deleteElection(authority: String, electionName: String) {
        data.elections.remove(electionName)
        data.candidates.remove(electionName)
        data.eligibleVoters.remove(electionName)
        // Remove all ballots for this election
        data.ballots.keys.removeIf { (election, _) -> election == electionName }
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        val candidates = data.candidates.getOrPut(electionName) { mutableSetOf() }
        candidates.addAll(candidateNames)
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        val candidates = data.candidates[electionName] ?: return
        candidates.removeAll(candidateNames.toSet())
    }

    override fun addVoters(authority: String, electionName: String, voterNames: List<String>) {
        val voters = data.eligibleVoters.getOrPut(electionName) { mutableSetOf() }
        voters.addAll(voterNames)
    }

    override fun removeVoters(authority: String, electionName: String, voterNames: List<String>) {
        val voters = data.eligibleVoters[electionName] ?: return
        voters.removeAll(voterNames.toSet())
    }

    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        now: Instant
    ) {
        val key = electionName to voterName
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
        // Find ballot by confirmation
        val entry = data.ballots.entries.find { it.value.confirmation == confirmation }
            ?: error("Ballot not found with confirmation: $confirmation")
        val ballot = entry.value
        data.ballots[entry.key] = ballot.copy(rankings = rankings)
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        // Find ballot by confirmation
        val entry = data.ballots.entries.find { it.value.confirmation == confirmation }
            ?: error("Ballot not found with confirmation: $confirmation")
        val ballot = entry.value
        data.ballots[entry.key] = ballot.copy(whenCast = now)
    }

    override fun setPassword(authority: String, userName: String, salt: String, hash: String) {
        val user = data.users[userName] ?: error("User not found: $userName")
        data.users[userName] = user.copy(salt = salt, hash = hash)
    }

    override fun setUserName(authority: String, oldUserName: String, newUserName: String) {
        val user = data.users[oldUserName] ?: error("User not found: $oldUserName")
        data.users.remove(oldUserName)
        data.users[newUserName] = user.copy(name = newUserName)

        // Update election ownership
        data.elections.values
            .filter { it.ownerName == oldUserName }
            .forEach { election ->
                data.elections[election.electionName] = election.copy(ownerName = newUserName)
            }

        // Update eligible voters
        data.eligibleVoters.values.forEach { voters ->
            if (voters.remove(oldUserName)) {
                voters.add(newUserName)
            }
        }

        // Update ballots
        val ballotsToUpdate = data.ballots.entries
            .filter { it.value.voterName == oldUserName }
            .toList()
        ballotsToUpdate.forEach { (key, ballot) ->
            data.ballots.remove(key)
            val newKey = key.first to newUserName
            data.ballots[newKey] = ballot.copy(voterName = newUserName)
        }
    }

    override fun setEmail(authority: String, userName: String, email: String) {
        val user = data.users[userName] ?: error("User not found: $userName")
        data.users[userName] = user.copy(email = email)
    }
}
