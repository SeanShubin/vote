package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.QueryLoader
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet

class MySqlQueryModel(
    private val connection: Connection,
    private val queryLoader: QueryLoader,
    private val json: Json
) : QueryModel {
    override fun findUserByName(name: String): User {
        val sql = queryLoader.load("user-select-by-name")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, name)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.toUser()
            } else {
                error("User not found: $name")
            }
        }
    }

    override fun findUserByEmail(email: String): User {
        val sql = queryLoader.load("user-select-by-email")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, email)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.toUser()
            } else {
                error("User not found with email: $email")
            }
        }
    }

    override fun searchUserByName(name: String): User? {
        val sql = queryLoader.load("user-select-by-name")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, name)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.toUser()
            } else {
                null
            }
        }
    }

    override fun searchUserByEmail(email: String): User? {
        val sql = queryLoader.load("user-select-by-email")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, email)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.toUser()
            } else {
                null
            }
        }
    }

    override fun userCount(): Int {
        val sql = queryLoader.load("user-count")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt("count")
            } else {
                0
            }
        }
    }

    override fun electionCount(): Int {
        val sql = queryLoader.load("election-count")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt("count")
            } else {
                0
            }
        }
    }

    override fun candidateCount(electionName: String): Int {
        val sql = queryLoader.load("candidate-count")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt("count")
            } else {
                0
            }
        }
    }

    override fun voterCount(electionName: String): Int {
        val sql = queryLoader.load("voter-count")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt("count")
            } else {
                0
            }
        }
    }

    override fun tableCount(): Int {
        // Count number of tables in the database (event_log, users, elections, candidates, eligible_voters, ballots, sync_state)
        return 7
    }

    override fun listUsers(): List<User> {
        val sql = queryLoader.load("user-select-all")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.toUser())
                }
            }
        }
    }

    override fun listElections(): List<ElectionSummary> {
        val sql = queryLoader.load("election-select-all")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.toElectionSummary())
                }
            }
        }
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

    override fun lastSynced(): Long? {
        val sql = queryLoader.load("sync-state-select")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getLong("last_synced")
            } else {
                null
            }
        }
    }

    override fun searchElectionByName(name: String): ElectionSummary? {
        val sql = queryLoader.load("election-select-by-name")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, name)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.toElectionSummary()
            } else {
                null
            }
        }
    }

    override fun listCandidates(electionName: String): List<String> {
        val sql = queryLoader.load("candidate-select-by-election")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.getString("candidate_name"))
                }
            }
        }
    }

    override fun listRankings(voterName: String, electionName: String): List<Ranking> {
        val sql = queryLoader.load("ballot-select-rankings")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val rankingsJson = rs.getString("rankings")
                json.decodeFromString(rankingsJson)
            } else {
                emptyList()
            }
        }
    }

    override fun listRankings(electionName: String): List<VoterElectionCandidateRank> {
        val sql = queryLoader.load("ballot-select-rankings-by-election")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    val voterName = rs.getString("voter_name")
                    val rankingsJson = rs.getString("rankings")
                    val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)
                    rankings.forEach { ranking ->
                        ranking.rank?.let { rank ->
                            add(
                                VoterElectionCandidateRank(
                                    voter = voterName,
                                    election = electionName,
                                    candidate = ranking.candidateName,
                                    rank = rank
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override fun searchBallot(voterName: String, electionName: String): BallotSummary? {
        val sql = queryLoader.load("ballot-select-summary")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                BallotSummary(
                    voterName = voterName,
                    electionName = electionName,
                    confirmation = rs.getString("confirmation"),
                    whenCast = Instant.fromEpochMilliseconds(rs.getTimestamp("when_cast").time)
                )
            } else {
                null
            }
        }
    }

    override fun listBallots(electionName: String): List<RevealedBallot> {
        val sql = queryLoader.load("ballot-select-all-by-election")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.toRevealedBallot(electionName, json))
                }
            }
        }
    }

    override fun listVoterNames(): List<String> {
        val sql = queryLoader.load("ballot-select-distinct-voters")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.getString("voter_name"))
                }
            }
        }
    }

    override fun listVotersForElection(electionName: String): List<String> {
        val sql = queryLoader.load("eligible-voter-select-by-election")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.getString("voter_name"))
                }
            }
        }
    }

    override fun listUserNames(): List<String> {
        val sql = queryLoader.load("user-select-names")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.getString("name"))
                }
            }
        }
    }

    override fun listPermissions(role: Role): List<Permission> {
        return Permission.entries.filter { roleHasPermission(role, it) }
    }

    private fun ResultSet.toUser(): User {
        return User(
            name = getString("name"),
            email = getString("email"),
            salt = getString("salt"),
            hash = getString("hash"),
            role = Role.valueOf(getString("role"))
        )
    }

    private fun ResultSet.toElectionSummary(): ElectionSummary {
        return ElectionSummary(
            electionName = getString("election_name"),
            ownerName = getString("owner_name"),
            secretBallot = getBoolean("secret_ballot"),
            noVotingBefore = getTimestamp("no_voting_before")?.let {
                Instant.fromEpochMilliseconds(it.time)
            },
            noVotingAfter = getTimestamp("no_voting_after")?.let {
                Instant.fromEpochMilliseconds(it.time)
            },
            allowEdit = getBoolean("allow_edit"),
            allowVote = getBoolean("allow_vote")
        )
    }

    private fun ResultSet.toRevealedBallot(electionName: String, json: Json): RevealedBallot {
        val rankingsJson = getString("rankings")
        val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)
        return RevealedBallot(
            voterName = getString("voter_name"),
            electionName = electionName,
            rankings = rankings,
            confirmation = getString("confirmation"),
            whenCast = Instant.fromEpochMilliseconds(getTimestamp("when_cast").time)
        )
    }
}
