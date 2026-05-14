package com.seanshubin.vote.backend.service

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.TableData

/**
 * Projects the underlying single-table DynamoDB items (or in-memory equivalents)
 * into the relational shape declared in `backend/src/main/resources/database/schema.sql`.
 *
 * Eight virtual tables; each projection is computed at read time by the corresponding
 * `project*` method. Foreign-key columns are annotated in the column header so the
 * admin browser can show "what each id represents" without separate metadata.
 *
 * Admin-only — gated by VIEW_SECRETS at the service layer.
 */
class DynamoToRelational(
    private val queryModel: QueryModel,
    private val eventLog: EventLog,
) {
    fun listDebugTableNames(): List<String> = listOf(
        USERS, ELECTIONS, CANDIDATES, BALLOTS, RANKINGS, SYNC_STATE, EVENT_LOG,
    )

    fun project(tableName: String): TableData = when (tableName) {
        USERS -> projectUsers()
        ELECTIONS -> projectElections()
        CANDIDATES -> projectCandidates()
        BALLOTS -> projectBallots()
        RANKINGS -> projectRankings()
        SYNC_STATE -> projectSyncState()
        EVENT_LOG -> projectEventLog()
        else -> throw IllegalArgumentException("Unknown debug table: $tableName")
    }

    private fun projectUsers(): TableData {
        val columns = listOf("name", "role", "discord_id", "discord_display_name")
        val rows = queryModel.listUsers().map { u ->
            listOf<String?>(u.name, u.role.name, u.discordId, u.discordDisplayName)
        }
        return TableData(USERS, columns, rows)
    }

    private fun projectElections(): TableData {
        val columns = listOf(
            "election_name",
            "owner_name (-> users.name)",
            "description",
        )
        val rows = queryModel.listElections().map { e ->
            listOf<String?>(
                e.electionName,
                e.ownerName,
                e.description,
            )
        }
        return TableData(ELECTIONS, columns, rows)
    }

    private fun projectCandidates(): TableData {
        val columns = listOf(
            "election_name (-> elections.election_name)",
            "candidate_name",
        )
        val rows = queryModel.listElections().flatMap { election ->
            queryModel.listCandidates(election.electionName).map { candidateName ->
                listOf<String?>(election.electionName, candidateName)
            }
        }
        return TableData(CANDIDATES, columns, rows)
    }

    private fun projectBallots(): TableData {
        val columns = listOf(
            "election_name (-> elections.election_name)",
            "voter_name (-> users.name)",
            "confirmation",
            "when_cast",
        )
        val rows = queryModel.listElections().flatMap { election ->
            queryModel.listBallots(election.electionName).map { ballot ->
                listOf<String?>(
                    ballot.electionName,
                    ballot.voterName,
                    ballot.confirmation,
                    ballot.whenCast.toString(),
                )
            }
        }
        return TableData(BALLOTS, columns, rows)
    }

    /**
     * Rankings are inlined in each ballot's `rankings` JSON in DynamoDB; we
     * explode them here and identify each row by the natural composite key
     * (election_name, voter_name, candidate_name) instead of the synthetic
     * ballot_id used in the SQL schema.
     */
    private fun projectRankings(): TableData {
        val columns = listOf(
            "election_name (-> elections.election_name)",
            "voter_name (-> users.name)",
            "candidate_name (-> candidates.candidate_name)",
            "rank",
        )
        val rows = queryModel.listElections().flatMap { election ->
            queryModel.listBallots(election.electionName).flatMap { ballot ->
                ballot.rankings.map { ranking ->
                    listOf<String?>(
                        ballot.electionName,
                        ballot.voterName,
                        ranking.candidateName,
                        ranking.rank?.toString(),
                    )
                }
            }
        }
        return TableData(RANKINGS, columns, rows)
    }

    private fun projectSyncState(): TableData {
        val columns = listOf("id", "last_synced")
        val lastSynced = queryModel.lastSynced()
        val rows = if (lastSynced == null) emptyList() else listOf(
            listOf<String?>("1", lastSynced.toString())
        )
        return TableData(SYNC_STATE, columns, rows)
    }

    private fun projectEventLog(): TableData {
        val columns = listOf("event_id", "created_at", "authority (-> users.name)", "event_type", "event_data")
        // eventsToSync(0) returns every event since the beginning of time. The
        // EventLog interface doesn't promise ordering, so sort explicitly here.
        // event_id is the canonical strict order (atomic counter, no ties), so
        // sorting by it gives true chronological order even when two events
        // share the same wall-clock millisecond. Descending so the most recent
        // events sit at the top — that's what an admin investigating an issue
        // typically wants to see first.
        val rows = eventLog.eventsToSync(0)
            .sortedByDescending { it.eventId }
            .map { envelope ->
                listOf<String?>(
                    envelope.eventId.toString(),
                    envelope.whenHappened.toString(),
                    envelope.authority,
                    envelope.event::class.simpleName,
                    envelope.event.toString(),
                )
            }
        return TableData(EVENT_LOG, columns, rows)
    }

    companion object {
        const val USERS = "users"
        const val ELECTIONS = "elections"
        const val CANDIDATES = "candidates"
        const val BALLOTS = "ballots"
        const val RANKINGS = "rankings"
        const val SYNC_STATE = "sync_state"
        const val EVENT_LOG = "event_log"
    }
}
