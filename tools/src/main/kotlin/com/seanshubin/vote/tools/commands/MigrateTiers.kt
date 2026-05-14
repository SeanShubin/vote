package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.MysqlClient
import com.seanshubin.vote.tools.lib.Output

/**
 * One-shot migration for the tier-as-annotation refactor.
 *
 * Pre-refactor data shape:
 *   rankings(ballot_id, candidate_name, rank)
 * with tier markers stored as candidate-name rows: e.g. a ballot ranking
 *   Alice=1, [S]=2, Bob=3, [A]=4, Carol=5
 * lives as five rows where rows 2 and 4 have candidate_name set to a tier
 * name from the election's tiers list.
 *
 * Post-refactor data shape:
 *   rankings(ballot_id, candidate_name, rank, tier)
 * with only candidate rows; tier markers don't exist in storage anymore.
 * The voter's "this candidate cleared tier T" intent rides on the `tier`
 * column. The Tally pipeline materializes the tier markers at compute time
 * via [projectBallot].
 *
 * What this command does (in order, all-or-nothing per ballot inside one
 * tx so a half-applied state is impossible):
 *   1. ALTER TABLE rankings ADD COLUMN tier VARCHAR(255) NULL (idempotent).
 *   2. For each election with tiers, for each ballot, read its rankings
 *      in rank order. Walk top-to-bottom; whenever we pass a tier-marker
 *      row, remember its name as `currentTier`. Every following candidate
 *      row gets `tier = currentTier` until the next marker (or the end of
 *      the list, where currentTier = null = cleared no tier).
 *      Wait — that's backwards. Above a marker means cleared. So we walk
 *      top-to-bottom and for each candidate, look forward to the *next*
 *      marker; that marker's name is the candidate's tier annotation.
 *   3. UPDATE the candidate rows with their derived tier annotation.
 *   4. DELETE the tier-marker rows.
 *
 * Run with --dry-run to preview which rows would change. Pass --force
 * to actually apply.
 */
class MigrateTiers : CliktCommand(name = "migrate-tiers") {
    private val dryRun by option("--dry-run", help = "Report changes without applying them").flag()
    private val force by option("--force", help = "Apply changes (default if --dry-run not set)").flag()

    override fun help(context: Context) =
        "Convert legacy rankings (tier markers as candidate-name rows) into the " +
            "tier-as-annotation form on the rankings table. One-shot; idempotent."

    override fun run() {
        val apply = force || !dryRun
        Output.banner("Tier-annotation migration (${if (apply) "APPLYING" else "DRY RUN"})")

        MysqlClient.connect().use { conn ->
            conn.autoCommit = false
            try {
                ensureTierColumn(conn, apply)
                val elections = electionsWithTiers(conn)
                if (elections.isEmpty()) {
                    println("No elections with tiers — nothing to migrate.")
                    return@use
                }
                var totalCandidateUpdates = 0
                var totalMarkerDeletes = 0
                for ((electionName, tiers) in elections) {
                    val tierSet = tiers.toSet()
                    val ballots = ballotIdsFor(conn, electionName)
                    if (ballots.isEmpty()) continue
                    println("\nElection: $electionName (tiers: ${tiers.joinToString(", ")})")
                    for (ballotId in ballots) {
                        val rows = rankingsFor(conn, ballotId)
                        val (updates, deletes) = computeBallotChanges(rows, tierSet)
                        if (updates.isEmpty() && deletes.isEmpty()) continue
                        println("  ballot $ballotId: ${updates.size} candidate tier annotations, ${deletes.size} marker rows to drop")
                        if (apply) {
                            applyUpdates(conn, ballotId, updates)
                            applyDeletes(conn, ballotId, deletes)
                        }
                        totalCandidateUpdates += updates.size
                        totalMarkerDeletes += deletes.size
                    }
                }
                if (apply) conn.commit() else conn.rollback()
                println("\nTotal: $totalCandidateUpdates candidate updates, $totalMarkerDeletes marker rows ${if (apply) "applied" else "would be deleted"}.")
                if (!apply) println("(dry run — pass --force to apply)")
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun ensureTierColumn(conn: java.sql.Connection, apply: Boolean) {
        val existsSql = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'rankings'
              AND column_name = 'tier'
        """.trimIndent()
        val present = conn.prepareStatement(existsSql).use { stmt ->
            val rs = stmt.executeQuery()
            rs.next() && rs.getInt(1) > 0
        }
        if (present) {
            println("rankings.tier column already exists.")
        } else {
            println("Adding rankings.tier column...")
            if (apply) {
                conn.prepareStatement(
                    "ALTER TABLE rankings ADD COLUMN tier VARCHAR(255) NULL"
                ).use { it.executeUpdate() }
            }
        }
    }

    private fun electionsWithTiers(conn: java.sql.Connection): List<Pair<String, List<String>>> {
        val sql = "SELECT election_name, tier_name, position FROM tiers ORDER BY election_name, position"
        val grouped = mutableMapOf<String, MutableList<String>>()
        conn.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                grouped.getOrPut(rs.getString(1)) { mutableListOf() }.add(rs.getString(2))
            }
        }
        return grouped.map { (k, v) -> k to v.toList() }
    }

    private fun ballotIdsFor(conn: java.sql.Connection, electionName: String): List<Long> {
        val sql = "SELECT ballot_id FROM ballots WHERE election_name = ? ORDER BY ballot_id"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, electionName)
            val rs = stmt.executeQuery()
            buildList { while (rs.next()) add(rs.getLong(1)) }
        }
    }

    private data class RankingRow(val candidateName: String, val rank: Int, val tier: String?)

    private fun rankingsFor(conn: java.sql.Connection, ballotId: Long): List<RankingRow> {
        val sql = "SELECT candidate_name, `rank`, tier FROM rankings WHERE ballot_id = ? ORDER BY `rank`"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, ballotId)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) add(RankingRow(rs.getString(1), rs.getInt(2), rs.getString(3)))
            }
        }
    }

    private fun computeBallotChanges(
        rows: List<RankingRow>,
        tierSet: Set<String>,
    ): Pair<List<Pair<String, String?>>, List<String>> {
        // updates: list of (candidate_name, new tier value) to UPDATE.
        // deletes: list of candidate_name (which are actually tier names) to DELETE.
        // For each candidate row at position i, the next tier-marker row at
        // some position j > i is the highest tier this candidate cleared.
        // No marker after them → cleared no tier (annotation = null).
        val updates = mutableListOf<Pair<String, String?>>()
        val deletes = mutableListOf<String>()
        rows.forEachIndexed { i, row ->
            if (row.candidateName in tierSet) {
                deletes += row.candidateName
            } else {
                val derivedTier = rows.drop(i + 1).firstOrNull { it.candidateName in tierSet }?.candidateName
                if (derivedTier != row.tier) {
                    updates += row.candidateName to derivedTier
                }
            }
        }
        return updates to deletes
    }

    private fun applyUpdates(
        conn: java.sql.Connection,
        ballotId: Long,
        updates: List<Pair<String, String?>>,
    ) {
        if (updates.isEmpty()) return
        val sql = "UPDATE rankings SET tier = ? WHERE ballot_id = ? AND candidate_name = ?"
        conn.prepareStatement(sql).use { stmt ->
            for ((candidate, tier) in updates) {
                stmt.setString(1, tier)
                stmt.setLong(2, ballotId)
                stmt.setString(3, candidate)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun applyDeletes(conn: java.sql.Connection, ballotId: Long, names: List<String>) {
        if (names.isEmpty()) return
        val sql = "DELETE FROM rankings WHERE ballot_id = ? AND candidate_name = ?"
        conn.prepareStatement(sql).use { stmt ->
            for (name in names) {
                stmt.setLong(1, ballotId)
                stmt.setString(2, name)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }
}
