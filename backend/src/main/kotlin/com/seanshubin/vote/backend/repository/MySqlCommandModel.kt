package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.QueryLoader
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant
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

    override fun setElectionOwner(authority: String, electionName: String, newOwnerName: String) {
        val sql = queryLoader.load("election-update-owner")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, newOwnerName)
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
        // Identify tiers being removed by this set so we can null-out
        // their dangling [Ranking.tier] annotations before churning the
        // tiers table. Without this cascade, dropping then re-adding a
        // tier with the same name would resurrect stale rankings.
        val previousTiers = connection.prepareStatement(
            queryLoader.load("tier-select-by-election")
        ).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
        val removed = previousTiers - tierNames.toSet()
        if (removed.isNotEmpty()) {
            val clearSql = queryLoader.load("ranking-clear-tier-by-name")
            connection.prepareStatement(clearSql).use { stmt ->
                for (tierName in removed) {
                    stmt.setString(1, electionName)
                    stmt.setString(2, tierName)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }

        // Replace the entire tier list atomically: delete-then-insert.
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

    override fun renameTier(authority: String, electionName: String, oldName: String, newName: String) {
        // Rewrite Ranking.tier annotations first, then the tier row.
        // Same ordering rule as renameCandidate: a concurrent reader should
        // see either the old name everywhere or the new name everywhere,
        // never a half-applied state where the tiers row has the new label
        // and the rankings still point at the old one (or vice-versa).
        val rankingSql = queryLoader.load("ranking-rename-tier")
        connection.prepareStatement(rankingSql).use { stmt ->
            stmt.setString(1, newName)
            stmt.setString(2, electionName)
            stmt.setString(3, oldName)
            stmt.executeUpdate()
        }
        val tierSql = queryLoader.load("tier-rename")
        connection.prepareStatement(tierSql).use { stmt ->
            stmt.setString(1, newName)
            stmt.setString(2, electionName)
            stmt.setString(3, oldName)
            stmt.executeUpdate()
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

    override fun renameCandidate(authority: String, electionName: String, oldName: String, newName: String) {
        // Rewrite ranking rows first, then the candidate row. Order matters
        // because rankings.candidate_name has no FK to candidates — if the
        // candidate row moved first, a concurrent reader could see ranking
        // rows referencing a candidate that no longer exists. Doing it in
        // this order means a reader sees either the old name everywhere or
        // the new name everywhere; never half-applied.
        val rankingSql = queryLoader.load("ranking-rename-candidate")
        connection.prepareStatement(rankingSql).use { stmt ->
            stmt.setString(1, newName)
            stmt.setString(2, electionName)
            stmt.setString(3, oldName)
            stmt.executeUpdate()
        }
        val candidateSql = queryLoader.load("candidate-rename")
        connection.prepareStatement(candidateSql).use { stmt ->
            stmt.setString(1, newName)
            stmt.setString(2, electionName)
            stmt.setString(3, oldName)
            stmt.executeUpdate()
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

        // Insert new rankings (only those with non-null rank). Tier
        // annotation persists alongside rank so a future tier rename can
        // rewrite it in place without touching the candidate's ordering.
        val validRankings = rankings.filter { it.rank != null }
        if (validRankings.isNotEmpty()) {
            val insertRankingSql = queryLoader.load("ranking-insert")
            connection.prepareStatement(insertRankingSql).use { stmt ->
                for (ranking in validRankings) {
                    stmt.setLong(1, ballotId)
                    stmt.setString(2, ranking.candidateName)
                    stmt.setInt(3, ranking.rank!!) // Safe because we filtered nulls
                    stmt.setString(4, ranking.tier) // null → SQL NULL
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

        // Insert new rankings (only those with non-null rank). Tier
        // annotation persists alongside rank so a future tier rename can
        // rewrite it in place without touching the candidate's ordering.
        val validRankings = rankings.filter { it.rank != null }
        if (validRankings.isNotEmpty()) {
            val insertRankingSql = queryLoader.load("ranking-insert")
            connection.prepareStatement(insertRankingSql).use { stmt ->
                for (ranking in validRankings) {
                    stmt.setLong(1, ballotId)
                    stmt.setString(2, ranking.candidateName)
                    stmt.setInt(3, ranking.rank!!) // Safe because we filtered nulls
                    stmt.setString(4, ranking.tier) // null → SQL NULL
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

    override fun createUserViaDiscord(
        authority: String,
        userName: String,
        discordId: String,
        discordDisplayName: String,
        role: Role,
    ) {
        val sql = queryLoader.load("user-insert-discord")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userName)
            stmt.setString(2, role.name)
            stmt.setString(3, discordId)
            stmt.setString(4, discordDisplayName)
            stmt.executeUpdate()
        }
    }

    override fun linkDiscordCredential(
        authority: String,
        userName: String,
        discordId: String,
        discordDisplayName: String,
    ) {
        val sql = queryLoader.load("user-update-link-discord")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, discordId)
            stmt.setString(2, discordDisplayName)
            stmt.setString(3, userName)
            stmt.executeUpdate()
        }
    }
}
