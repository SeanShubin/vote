package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.domain.ElectionUpdates
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Timestamp

class MySqlCommandModel(
    private val connection: Connection,
    private val json: Json
) : CommandModel {
    override fun setLastSynced(lastSynced: Long) {
        val sql = "UPDATE sync_state SET last_synced = ? WHERE id = 1"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, lastSynced)
            stmt.executeUpdate()
        }
    }

    override fun initializeLastSynced(lastSynced: Long) {
        val sql = """
            INSERT INTO sync_state (id, last_synced) VALUES (1, ?)
            ON DUPLICATE KEY UPDATE last_synced = VALUES(last_synced)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, lastSynced)
            stmt.executeUpdate()
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
        val sql = "INSERT INTO users (name, email, salt, hash, role) VALUES (?, ?, ?, ?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userName)
            stmt.setString(2, email)
            stmt.setString(3, salt)
            stmt.setString(4, hash)
            stmt.setString(5, role.name)
            stmt.executeUpdate()
        }
    }

    override fun setRole(authority: String, userName: String, role: Role) {
        val sql = "UPDATE users SET role = ? WHERE name = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, role.name)
            stmt.setString(2, userName)
            stmt.executeUpdate()
        }
    }

    override fun removeUser(authority: String, userName: String) {
        // Cascading deletes handled by foreign key constraints
        val sql = "DELETE FROM users WHERE name = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userName)
            stmt.executeUpdate()
        }
    }

    override fun addElection(authority: String, owner: String, electionName: String) {
        val sql = "INSERT INTO elections (election_name, owner_name) VALUES (?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, owner)
            stmt.executeUpdate()
        }
    }

    override fun updateElection(authority: String, electionName: String, updates: ElectionUpdates) {
        val setClauses = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        updates.newElectionName?.let {
            setClauses.add("election_name = ?")
            params.add(it)
        }
        updates.secretBallot?.let {
            setClauses.add("secret_ballot = ?")
            params.add(it)
        }
        if (updates.clearNoVotingBefore == true) {
            setClauses.add("no_voting_before = NULL")
        } else {
            updates.noVotingBefore?.let {
                setClauses.add("no_voting_before = ?")
                params.add(Timestamp(it.toEpochMilliseconds()))
            }
        }
        if (updates.clearNoVotingAfter == true) {
            setClauses.add("no_voting_after = NULL")
        } else {
            updates.noVotingAfter?.let {
                setClauses.add("no_voting_after = ?")
                params.add(Timestamp(it.toEpochMilliseconds()))
            }
        }
        updates.allowVote?.let {
            setClauses.add("allow_vote = ?")
            params.add(it)
        }
        updates.allowEdit?.let {
            setClauses.add("allow_edit = ?")
            params.add(it)
        }

        if (setClauses.isEmpty()) return

        val sql = "UPDATE elections SET ${setClauses.joinToString(", ")} WHERE election_name = ?"
        params.add(electionName)

        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                when (param) {
                    is String -> stmt.setString(index + 1, param)
                    is Boolean -> stmt.setBoolean(index + 1, param)
                    is Timestamp -> stmt.setTimestamp(index + 1, param)
                    else -> stmt.setObject(index + 1, param)
                }
            }
            stmt.executeUpdate()
        }
    }

    override fun deleteElection(authority: String, electionName: String) {
        // Cascading deletes handled by foreign key constraints
        val sql = "DELETE FROM elections WHERE election_name = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.executeUpdate()
        }
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return
        val sql = "INSERT IGNORE INTO candidates (election_name, candidate_name) VALUES (?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            for (candidateName in candidateNames) {
                stmt.setString(1, electionName)
                stmt.setString(2, candidateName)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return
        val sql = "DELETE FROM candidates WHERE election_name = ? AND candidate_name = ?"
        connection.prepareStatement(sql).use { stmt ->
            for (candidateName in candidateNames) {
                stmt.setString(1, electionName)
                stmt.setString(2, candidateName)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun addVoters(authority: String, electionName: String, voterNames: List<String>) {
        if (voterNames.isEmpty()) return
        val sql = "INSERT IGNORE INTO eligible_voters (election_name, voter_name) VALUES (?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            for (voterName in voterNames) {
                stmt.setString(1, electionName)
                stmt.setString(2, voterName)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun removeVoters(authority: String, electionName: String, voterNames: List<String>) {
        if (voterNames.isEmpty()) return
        val sql = "DELETE FROM eligible_voters WHERE election_name = ? AND voter_name = ?"
        connection.prepareStatement(sql).use { stmt ->
            for (voterName in voterNames) {
                stmt.setString(1, electionName)
                stmt.setString(2, voterName)
                stmt.addBatch()
            }
            stmt.executeBatch()
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
        val rankingsJson = json.encodeToString(rankings)
        val sql = """
            INSERT INTO ballots (election_name, voter_name, rankings, confirmation, when_cast)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            rankings = VALUES(rankings),
            confirmation = VALUES(confirmation),
            when_cast = VALUES(when_cast)
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
            stmt.setString(3, rankingsJson)
            stmt.setString(4, confirmation)
            stmt.setTimestamp(5, Timestamp(now.toEpochMilliseconds()))
            stmt.executeUpdate()
        }
    }

    override fun setRankings(authority: String, confirmation: String, electionName: String, rankings: List<Ranking>) {
        val rankingsJson = json.encodeToString(rankings)
        val sql = "UPDATE ballots SET rankings = ? WHERE confirmation = ? AND election_name = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, rankingsJson)
            stmt.setString(2, confirmation)
            stmt.setString(3, electionName)
            stmt.executeUpdate()
        }
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        val sql = "UPDATE ballots SET when_cast = ? WHERE confirmation = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp(now.toEpochMilliseconds()))
            stmt.setString(2, confirmation)
            stmt.executeUpdate()
        }
    }

    override fun setPassword(authority: String, userName: String, salt: String, hash: String) {
        val sql = "UPDATE users SET salt = ?, hash = ? WHERE name = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, salt)
            stmt.setString(2, hash)
            stmt.setString(3, userName)
            stmt.executeUpdate()
        }
    }

    override fun setUserName(authority: String, oldUserName: String, newUserName: String) {
        // MySQL will cascade updates if we set up ON UPDATE CASCADE in foreign keys
        // But our schema uses ON DELETE CASCADE only, so we need manual updates

        val updateUser = "UPDATE users SET name = ? WHERE name = ?"
        connection.prepareStatement(updateUser).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }

        // Update references in elections
        val updateElections = "UPDATE elections SET owner_name = ? WHERE owner_name = ?"
        connection.prepareStatement(updateElections).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }

        // Update references in eligible_voters
        val updateVoters = "UPDATE eligible_voters SET voter_name = ? WHERE voter_name = ?"
        connection.prepareStatement(updateVoters).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }

        // Update references in ballots
        val updateBallots = "UPDATE ballots SET voter_name = ? WHERE voter_name = ?"
        connection.prepareStatement(updateBallots).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }
    }

    override fun setEmail(authority: String, userName: String, email: String) {
        val sql = "UPDATE users SET email = ? WHERE name = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, email)
            stmt.setString(2, userName)
            stmt.executeUpdate()
        }
    }
}
