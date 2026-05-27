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
        // Cascade: drop this user from every election's manager list. The
        // election_managers.user_name column has no FK, so MySQL handles this
        // via an explicit delete-by-user too.
        data.electionManagers.values.forEach { managers ->
            managers.removeAll { it.equals(userName, ignoreCase = true) }
        }
        // Cascade: drop every candidate note this user authored.
        val voterKey = userName.lowercase()
        data.candidateNotes.keys.removeAll { it.third == voterKey }
    }

    override fun addElection(authority: String, owner: String, electionName: String, description: String) {
        val key = electionName.lowercase()
        data.elections[key] = InMemoryData.ElectionData(
            ownerName = owner,
            electionName = electionName,
            description = description,
        )
        data.candidates[key] = mutableSetOf()
        data.electionManagers[key] = mutableSetOf()
    }

    override fun setElectionDescription(authority: String, electionName: String, description: String) {
        val key = electionName.lowercase()
        val election = data.elections[key] ?: error("Election not found: $electionName")
        data.elections[key] = election.copy(description = description)
    }

    override fun renameElection(authority: String, oldName: String, newName: String) {
        val oldKey = oldName.lowercase()
        val newKey = newName.lowercase()
        val election = data.elections.remove(oldKey) ?: error("Election not found: $oldName")
        data.elections[newKey] = election.copy(electionName = newName)

        // Re-key every per-election collection. On a case-only rename
        // oldKey == newKey, so remove()-then-put() under the same key just
        // rewrites the entry — still correct.
        data.candidates.remove(oldKey)?.let { data.candidates[newKey] = it }
        data.tiers.remove(oldKey)?.let { data.tiers[newKey] = it }
        data.electionManagers.remove(oldKey)?.let { data.electionManagers[newKey] = it }

        // Ballots are keyed by (electionKey, voterKey); re-key each and
        // rewrite the stored display-case electionName.
        data.ballots.entries
            .filter { it.key.first == oldKey }
            .toList()
            .forEach { (key, ballot) ->
                data.ballots.remove(key)
                data.ballots[newKey to key.second] = ballot.copy(electionName = newName)
            }

        // Candidate notes are keyed by (electionKey, candidateKey, voterKey);
        // re-key each and rewrite the stored display-case electionName.
        data.candidateNotes.entries
            .filter { it.key.first == oldKey }
            .toList()
            .forEach { (key, note) ->
                data.candidateNotes.remove(key)
                data.candidateNotes[Triple(newKey, key.second, key.third)] =
                    note.copy(electionName = newName)
            }
    }

    override fun setElectionOwner(authority: String, electionName: String, newOwnerName: String) {
        val key = electionName.lowercase()
        val election = data.elections[key] ?: error("Election not found: $electionName")
        data.elections[key] = election.copy(ownerName = newOwnerName)
    }

    override fun addElectionManager(authority: String, electionName: String, userName: String) {
        val managers = data.electionManagers.getOrPut(electionName.lowercase()) { mutableSetOf() }
        // Drop any case-variant of this name first so the set never carries
        // two rows for the same user; the resolved canonical name wins.
        managers.removeAll { it.equals(userName, ignoreCase = true) }
        managers.add(userName)
    }

    override fun removeElectionManager(authority: String, electionName: String, userName: String) {
        data.electionManagers[electionName.lowercase()]?.removeAll { it.equals(userName, ignoreCase = true) }
    }

    override fun deleteElection(authority: String, electionName: String) {
        val key = electionName.lowercase()
        data.elections.remove(key)
        data.candidates.remove(key)
        data.tiers.remove(key)
        data.electionManagers.remove(key)
        data.ballots.keys.removeIf { (election, _) -> election == key }
        data.candidateNotes.keys.removeIf { (election, _, _) -> election == key }
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        val candidates = data.candidates.getOrPut(electionName.lowercase()) { mutableSetOf() }
        // Idempotent on case-insensitive match: if "Alice" already exists,
        // re-adding "alice" is a no-op (the original display case wins).
        // Within the input list itself, the first occurrence wins for the
        // same reason.
        val existingKeys = candidates.mapTo(mutableSetOf()) { it.lowercase() }
        candidateNames.forEach { name ->
            val key = name.lowercase()
            if (existingKeys.add(key)) candidates.add(name)
        }
    }

    override fun setTiers(authority: String, electionName: String, tierNames: List<String>) {
        // Compute which tiers are being removed by this set: any tier that
        // existed but is not in the new list. Cascade by nulling the
        // [Ranking.tier] annotation on every ballot ranking that
        // referenced one of those dropped tiers — the voter's "this
        // candidate cleared tier X" claim no longer has a target tier.
        // The candidate's rank is left alone; only the tier label is
        // cleared. Without this cascade, dropped-then-re-added tiers
        // would resurrect stale rankings.
        val key = electionName.lowercase()
        val previousKeys = data.tiers[key].orEmpty().mapTo(mutableSetOf()) { it.lowercase() }
        val newKeys = tierNames.mapTo(mutableSetOf()) { it.lowercase() }
        val removedKeys = previousKeys - newKeys
        if (tierNames.isEmpty()) {
            data.tiers.remove(key)
        } else {
            data.tiers[key] = tierNames.toList()
        }
        if (removedKeys.isNotEmpty()) {
            clearTierAnnotations(key, removedKeys)
        }
    }

    override fun renameTier(authority: String, electionName: String, oldName: String, newName: String) {
        // Tier list: swap the label in place, preserving its position.
        val key = electionName.lowercase()
        val tiers = data.tiers[key] ?: return
        val oldKey = oldName.lowercase()
        val idx = tiers.indexOfFirst { it.lowercase() == oldKey }
        if (idx < 0) return
        data.tiers[key] = tiers.toMutableList().also { it[idx] = newName }
        // Rankings: rewrite every Ranking.tier annotation that pointed at
        // the old label so it now points at the new label. Rank values
        // and the rest of the ranking are untouched.
        data.ballots.entries
            .filter { (k, _) -> k.first == key }
            .forEach { (k, ballot) ->
                val rewritten = ballot.rankings.map { ranking ->
                    if (ranking.tier?.lowercase() == oldKey) ranking.copy(tier = newName) else ranking
                }
                if (rewritten != ballot.rankings) {
                    data.ballots[k] = ballot.copy(rankings = rewritten)
                }
            }
    }

    private fun clearTierAnnotations(electionKey: String, removedTierKeys: Set<String>) {
        data.ballots.entries
            .filter { (k, _) -> k.first == electionKey }
            .forEach { (k, ballot) ->
                val rewritten = ballot.rankings.map { ranking ->
                    if (ranking.tier?.lowercase() in removedTierKeys) ranking.copy(tier = null) else ranking
                }
                if (rewritten != ballot.rankings) {
                    data.ballots[k] = ballot.copy(rankings = rewritten)
                }
            }
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        val key = electionName.lowercase()
        val candidates = data.candidates[key] ?: return
        val removedKeys = candidateNames.mapTo(mutableSetOf()) { it.lowercase() }
        candidates.removeAll { it.lowercase() in removedKeys }
        // Cascade: strip the removed candidate names from any existing ballot's
        // ranking list in this election. Without this, ballots would carry
        // ghost rankings for candidates that no longer exist.
        data.ballots.entries
            .filter { (k, _) -> k.first == key }
            .forEach { (k, ballot) ->
                val filtered = ballot.rankings.filter { it.candidateName.lowercase() !in removedKeys }
                if (filtered.size != ballot.rankings.size) {
                    data.ballots[k] = ballot.copy(rankings = filtered)
                }
            }
        // Cascade: drop notes for the removed candidates in this election.
        data.candidateNotes.keys.removeAll { (e, c, _) -> e == key && c in removedKeys }
    }

    override fun renameCandidate(authority: String, electionName: String, oldName: String, newName: String) {
        val key = electionName.lowercase()
        val candidates = data.candidates[key] ?: return
        val oldKey = oldName.lowercase()
        if (!candidates.removeAll { it.lowercase() == oldKey }) return
        candidates.add(newName)
        data.ballots.entries
            .filter { (k, _) -> k.first == key }
            .forEach { (k, ballot) ->
                val rewritten = ballot.rankings.map { ranking ->
                    if (ranking.candidateName.lowercase() == oldKey) ranking.copy(candidateName = newName) else ranking
                }
                if (rewritten != ballot.rankings) {
                    data.ballots[k] = ballot.copy(rankings = rewritten)
                }
            }
        // Cascade: re-key + relabel notes attached to the renamed candidate.
        val newKey = newName.lowercase()
        data.candidateNotes.entries
            .filter { it.key.first == key && it.key.second == oldKey }
            .toList()
            .forEach { (k, note) ->
                data.candidateNotes.remove(k)
                data.candidateNotes[Triple(key, newKey, k.third)] =
                    note.copy(candidateName = newName)
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
        val key = electionName.lowercase() to voterName.lowercase()
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
        data.ballots.remove(electionName.lowercase() to voterName.lowercase())
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
        data.elections.entries
            .filter { (_, election) -> election.ownerName.equals(oldUserName, ignoreCase = true) }
            .forEach { (k, election) ->
                data.elections[k] = election.copy(ownerName = newUserName)
            }

        // Update election-manager entries — same case-insensitive match, since
        // the user could be a co-manager on elections they don't own.
        data.electionManagers.values.forEach { managers ->
            if (managers.removeAll { it.equals(oldUserName, ignoreCase = true) }) {
                managers.add(newUserName)
            }
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

        // Update candidate notes — re-key by the new voter slot and rewrite
        // the stored display-case voterName.
        data.candidateNotes.entries
            .filter { it.value.voterName.equals(oldUserName, ignoreCase = true) }
            .toList()
            .forEach { (key, note) ->
                data.candidateNotes.remove(key)
                data.candidateNotes[Triple(key.first, key.second, newKey)] =
                    note.copy(voterName = newUserName)
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

    override fun setDiscordDisplayName(
        authority: String,
        userName: String,
        discordDisplayName: String,
    ) {
        val key = userName.lowercase()
        val user = data.users[key] ?: error("User not found: $userName")
        data.users[key] = user.copy(discordDisplayName = discordDisplayName)
    }

    override fun setCandidateNote(
        authority: String,
        electionName: String,
        candidateName: String,
        voterName: String,
        text: String,
        lastUpdated: Instant,
    ) {
        val key = Triple(electionName.lowercase(), candidateName.lowercase(), voterName.lowercase())
        data.candidateNotes[key] = InMemoryData.CandidateNoteData(
            electionName = electionName,
            candidateName = candidateName,
            voterName = voterName,
            text = text,
            lastUpdated = lastUpdated,
        )
    }

    override fun deleteCandidateNote(
        authority: String,
        electionName: String,
        candidateName: String,
        voterName: String,
    ) {
        val key = Triple(electionName.lowercase(), candidateName.lowercase(), voterName.lowercase())
        data.candidateNotes.remove(key)
    }
}
