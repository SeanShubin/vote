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
        // Blank email never matches anyone — emailless users share the
        // empty string as their stored value, but the email-lookup path
        // is reserved for users who actually provided one.
        if (email.isEmpty()) return null
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

    override fun ballotCount(electionName: String): Int {
        val sql = queryLoader.load("ballot-count")
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
        // event_log, users, elections, candidates, tiers, ballots, rankings, sync_state
        return 8
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

    override fun candidateBallotCounts(electionName: String): Map<String, Int> {
        val sql = queryLoader.load("candidate-ballot-counts")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildMap {
                while (rs.next()) {
                    put(rs.getString("candidate_name"), rs.getInt("ballot_count"))
                }
            }
        }
    }

    override fun listTiers(electionName: String): List<String> {
        val sql = queryLoader.load("tier-select-by-election")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(rs.getString("tier_name"))
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
            buildList {
                while (rs.next()) {
                    add(Ranking(
                        candidateName = rs.getString("candidate_name"),
                        rank = rs.getInt("rank"),
                        tier = rs.getString("tier"),
                    ))
                }
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
                    add(
                        VoterElectionCandidateRank(
                            voter = rs.getString("voter_name"),
                            election = electionName,
                            candidate = rs.getString("candidate_name"),
                            rank = rs.getInt("rank")
                        )
                    )
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

    override fun listBallots(electionName: String): List<Ballot.Revealed> {
        val sql = queryLoader.load("ballot-select-all-by-election")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()

            // Group rows by voter since each ranking is a separate row
            val ballotsByVoter = mutableMapOf<String, MutableList<Pair<Ranking, BallotMetadata>>>()
            while (rs.next()) {
                val voterName = rs.getString("voter_name")
                val ranking = Ranking(
                    candidateName = rs.getString("candidate_name"),
                    rank = rs.getInt("rank"),
                    tier = rs.getString("tier"), // null when SQL NULL
                )
                val metadata = BallotMetadata(
                    confirmation = rs.getString("confirmation"),
                    whenCast = Instant.fromEpochMilliseconds(rs.getTimestamp("when_cast").time)
                )
                ballotsByVoter.getOrPut(voterName) { mutableListOf() }.add(ranking to metadata)
            }

            // Convert to Ballot.Revealed list
            ballotsByVoter.map { (voterName, rankingsWithMetadata) ->
                val rankings = rankingsWithMetadata.map { it.first }
                val metadata = rankingsWithMetadata.first().second // All rows have same metadata
                Ballot.Revealed(
                    voterName = voterName,
                    electionName = electionName,
                    rankings = rankings,
                    confirmation = metadata.confirmation,
                    whenCast = metadata.whenCast
                )
            }
        }
    }

    private data class BallotMetadata(
        val confirmation: String,
        val whenCast: Instant
    )

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

    override fun electionsOwnedCount(userName: String): Int {
        val sql = queryLoader.load("election-count-owned-by")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userName)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    override fun ballotsCastCount(userName: String): Int {
        val sql = queryLoader.load("ballot-count-cast-by")
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userName)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    private fun ResultSet.toUser(): User {
        return User(
            name = getString("name"),
            // SQL NULL on email represents "no email on file"; translate
            // back to the domain's empty-string sentinel so the rest of
            // the application treats both backends identically.
            email = getString("email") ?: "",
            salt = getString("salt"),
            hash = getString("hash"),
            role = Role.valueOf(getString("role"))
        )
    }

    private fun ResultSet.toElectionSummary(): ElectionSummary {
        return ElectionSummary(
            electionName = getString("election_name"),
            ownerName = getString("owner_name"),
            description = getString("description") ?: "",
        )
    }
}
