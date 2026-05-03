package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.QueryLoader
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
            // Translate the domain's "" sentinel for "no email" to SQL NULL
            // so the UNIQUE constraint on email permits multiple emailless
            // users to coexist (NULL is not equal to NULL in UNIQUE).
            if (email.isEmpty()) stmt.setNull(2, java.sql.Types.VARCHAR)
            else stmt.setString(2, email)
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

    override fun addElection(authority: String, owner: String, electionName: String, description: String) {
        val sql = queryLoader.load("election-insert")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, owner)
            stmt.setString(3, description)
            stmt.executeUpdate()
        }
    }

    override fun setElectionDescription(authority: String, electionName: String, description: String) {
        val sql = queryLoader.load("election-update-description")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, description)
            stmt.setString(2, electionName)
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

    override fun setTiers(authority: String, electionName: String, tierNames: List<String>) {
        // Replace the entire tier list atomically: delete-then-insert. The
        // service layer enforces that this only fires when no ballots exist,
        // so churning the rows here can't orphan ranking data.
        val deleteSql = queryLoader.load("tier-delete-by-election")
        connection.prepareStatement(deleteSql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.executeUpdate()
        }

        if (tierNames.isEmpty()) return

        val insertSql = queryLoader.load("tier-insert")
        connection.prepareStatement(insertSql).use { stmt ->
            tierNames.forEachIndexed { position, tierName ->
                stmt.setString(1, electionName)
                stmt.setInt(2, position)
                stmt.setString(3, tierName)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return

        // Cascade ranking rows first. The rankings table doesn't have an FK on
        // candidate_name (just a string column), so without this the candidate
        // row vanishes while ranking rows referencing it survive as ghosts.
        val rankingDeleteSql = queryLoader.load("ranking-delete-by-candidate")
        connection.prepareStatement(rankingDeleteSql).use { stmt ->
            for (candidateName in candidateNames) {
                stmt.setString(1, electionName)
                stmt.setString(2, candidateName)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

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

    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        now: Instant
    ) {
        // Insert or update ballot
        val ballotSql = queryLoader.load("ballot-upsert")
        connection.prepareStatement(ballotSql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
            stmt.setString(3, confirmation)
            stmt.setTimestamp(4, Timestamp(now.toEpochMilliseconds()))
            stmt.executeUpdate()
        }

        // Get ballot_id
        val ballotIdSql = queryLoader.load("ballot-select-id")
        val ballotId = connection.prepareStatement(ballotIdSql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getLong(1) else throw IllegalStateException("Ballot not found after insert")
        }

        // Delete old rankings (if any)
        val deleteRankingsSql = queryLoader.load("ranking-delete-by-ballot-id")
        connection.prepareStatement(deleteRankingsSql).use { stmt ->
            stmt.setLong(1, ballotId)
            stmt.executeUpdate()
        }

        // Insert new rankings (only those with non-null rank)
        val validRankings = rankings.filter { it.rank != null }
        if (validRankings.isNotEmpty()) {
            val insertRankingSql = queryLoader.load("ranking-insert")
            connection.prepareStatement(insertRankingSql).use { stmt ->
                for (ranking in validRankings) {
                    stmt.setLong(1, ballotId)
                    stmt.setString(2, ranking.candidateName)
                    stmt.setInt(3, ranking.rank!!) // Safe because we filtered nulls
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    override fun setRankings(authority: String, confirmation: String, electionName: String, rankings: List<Ranking>) {
        // Get ballot_id
        val ballotIdSql = queryLoader.load("ballot-select-id-by-confirmation")
        val ballotId = connection.prepareStatement(ballotIdSql).use { stmt ->
            stmt.setString(1, confirmation)
            stmt.setString(2, electionName)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getLong(1) else throw IllegalStateException("Ballot not found: confirmation=$confirmation, election=$electionName")
        }

        // Delete old rankings
        val deleteRankingsSql = queryLoader.load("ranking-delete-by-ballot-id")
        connection.prepareStatement(deleteRankingsSql).use { stmt ->
            stmt.setLong(1, ballotId)
            stmt.executeUpdate()
        }

        // Insert new rankings (only those with non-null rank)
        val validRankings = rankings.filter { it.rank != null }
        if (validRankings.isNotEmpty()) {
            val insertRankingSql = queryLoader.load("ranking-insert")
            connection.prepareStatement(insertRankingSql).use { stmt ->
                for (ranking in validRankings) {
                    stmt.setLong(1, ballotId)
                    stmt.setString(2, ranking.candidateName)
                    stmt.setInt(3, ranking.rank!!) // Safe because we filtered nulls
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
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

    override fun deleteBallot(authority: String, voterName: String, electionName: String) {
        // Look up ballot_id so we can drop the ranking rows first; the rankings
        // table has an FK on ballot_id but no ON DELETE CASCADE in schema, so we
        // mirror the explicit cascade pattern used elsewhere (e.g. removeCandidates).
        val ballotIdSql = queryLoader.load("ballot-select-id")
        val ballotId = connection.prepareStatement(ballotIdSql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getLong(1) else null
        } ?: return

        val deleteRankingsSql = queryLoader.load("ranking-delete-by-ballot-id")
        connection.prepareStatement(deleteRankingsSql).use { stmt ->
            stmt.setLong(1, ballotId)
            stmt.executeUpdate()
        }

        val deleteBallotSql = queryLoader.load("ballot-delete")
        connection.prepareStatement(deleteBallotSql).use { stmt ->
            stmt.setString(1, electionName)
            stmt.setString(2, voterName)
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
            // See createUser — empty string becomes NULL so UNIQUE permits
            // multiple emailless users.
            if (email.isEmpty()) stmt.setNull(1, java.sql.Types.VARCHAR)
            else stmt.setString(1, email)
            stmt.setString(2, userName)
            stmt.executeUpdate()
        }
    }
}
