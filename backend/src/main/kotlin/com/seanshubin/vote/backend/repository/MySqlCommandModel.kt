package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.QueryLoader
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
    private val queryLoader: QueryLoader,
    private val json: Json
) : CommandModel {
    override fun setLastSynced(lastSynced: Long) {
        val sql = queryLoader.load("sync-state-update")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, lastSynced)
            stmt.executeUpdate()
        }
    }

    override fun initializeLastSynced(lastSynced: Long) {
        val sql = queryLoader.load("sync-state-upsert")
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
        val sql = queryLoader.load("user-insert")
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
        val sql = queryLoader.load("user-update-role")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, role.name)
            stmt.setString(2, userName)
            stmt.executeUpdate()
        }
    }

    override fun removeUser(authority: String, userName: String) {
        val sql = queryLoader.load("user-delete")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userName)
            stmt.executeUpdate()
        }
    }

    override fun addElection(authority: String, owner: String, electionName: String) {
        val sql = queryLoader.load("election-insert")
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
        val sql = queryLoader.load("election-delete")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.executeUpdate()
        }
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return
        val sql = queryLoader.load("candidate-insert")
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
        val sql = queryLoader.load("candidate-delete")
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
        val sql = queryLoader.load("eligible-voter-insert")
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
        val sql = queryLoader.load("eligible-voter-delete")
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
        val sql = queryLoader.load("ballot-upsert")

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
        val sql = queryLoader.load("ballot-update-rankings")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, rankingsJson)
            stmt.setString(2, confirmation)
            stmt.setString(3, electionName)
            stmt.executeUpdate()
        }
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        val sql = queryLoader.load("ballot-update-when-cast")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp(now.toEpochMilliseconds()))
            stmt.setString(2, confirmation)
            stmt.executeUpdate()
        }
    }

    override fun setPassword(authority: String, userName: String, salt: String, hash: String) {
        val sql = queryLoader.load("user-update-password")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, salt)
            stmt.setString(2, hash)
            stmt.setString(3, userName)
            stmt.executeUpdate()
        }
    }

    override fun setUserName(authority: String, oldUserName: String, newUserName: String) {
        val updateUser = queryLoader.load("user-update-name")
        connection.prepareStatement(updateUser).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }

        val updateElections = queryLoader.load("election-update-owner-name")
        connection.prepareStatement(updateElections).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }

        val updateVoters = queryLoader.load("eligible-voter-update-name")
        connection.prepareStatement(updateVoters).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }

        val updateBallots = queryLoader.load("ballot-update-voter-name")
        connection.prepareStatement(updateBallots).use { stmt ->
            stmt.setString(1, newUserName)
            stmt.setString(2, oldUserName)
            stmt.executeUpdate()
        }
    }

    override fun setEmail(authority: String, userName: String, email: String) {
        val sql = queryLoader.load("user-update-email")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, email)
            stmt.setString(2, userName)
            stmt.executeUpdate()
        }
    }
}
