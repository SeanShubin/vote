package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.backend.repository.DynamoDbEventLog
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableCommandModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableQueryModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.backend.service.EventApplier
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis

/**
 * Idempotent reconciliation of the vote_data projection table against the
 * shape declared in [DynamoDbSingleTableSchema.expectedMainTableShape].
 *
 * If the live shape already matches, the command exits without touching
 * production. If it differs (added/removed/changed GSIs, key changes, new
 * attribute definitions), the command:
 *
 *   1. Pauses the event log so no writes land mid-rebuild.
 *   2. Drops vote_data.
 *   3. Recreates it via CreateTable with all GSIs declared up front —
 *      sidestepping DynamoDB's 1-GSI-per-UpdateTable limit.
 *   4. Initializes the sync cursor to 0.
 *   5. Replays the entire event log into the new (empty) projection;
 *      the new GSIs populate as the projection writes happen, so there
 *      is no separate backfill phase.
 *   6. Resumes the event log.
 *
 * Run as the post-deploy step in CI on every push — when nothing changed
 * it is a fast no-op (one DescribeTable call); when the projection shape
 * changed it is the canonical reconciliation. Same code path is reachable
 * by hand for local recovery.
 */
class RebuildProjection : CliktCommand(name = "rebuild-projection") {
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()
    private val checkOnly by option(
        "--check",
        help = "Exit 0 if the live table matches the expected shape, exit 1 if it differs. Does not modify anything.",
    ).flag()

    override fun help(context: Context) =
        "Rebuild vote_data from vote_event_log if its shape no longer matches the code's expected shape. Idempotent: no-op when shape matches."

    override fun run() {
        val target = DynamoClient.describe(prod)
        runBlocking {
            DynamoClient.createFor(prod).use { client ->
                val live = DynamoDbSingleTableSchema.readLiveMainTableShape(client)
                val diff = DynamoDbSingleTableSchema.expectedMainTableShape.diffFrom(live)

                if (diff == null) {
                    Output.success("vote_data shape matches expected; no rebuild needed on $target.")
                    return@runBlocking
                }

                Output.banner("Projection rebuild required on $target")
                println("Reason: $diff")

                if (checkOnly) {
                    Output.error("Shape mismatch (--check). Run without --check to rebuild.")
                }

                if (!yes) requireConfirmation(prod)

                rebuild(client)
            }
        }
    }

    private suspend fun rebuild(client: DynamoDbClient) {
        val json = Json { ignoreUnknownKeys = true }
        val eventLog = DynamoDbEventLog(client, json)
        val commandModel = DynamoDbSingleTableCommandModel(client, json)
        val queryModel = DynamoDbSingleTableQueryModel(client, json)

        println("Pausing event log...")
        eventLog.setPaused(true)

        try {
            println("Dropping vote_data...")
            DynamoDbSingleTableSchema.deleteMainTable(client)
            waitForTableGone(client)

            println("Recreating vote_data with expected shape...")
            DynamoDbSingleTableSchema.createMainTable(
                client,
                DynamoDbSingleTableSchema.expectedMainTableShape,
            )
            waitForTableActive(client)

            // Cursor lives in vote_data itself, so the freshly-created table
            // has no SYNC row — start at 0 so synchronize() replays
            // everything in the event log.
            commandModel.initializeLastSynced(0)

            val applier = EventApplier(eventLog, commandModel, queryModel)
            val eventCount = eventLog.eventCount()
            println("Replaying $eventCount events into the rebuilt projection...")
            val elapsedMs = measureTimeMillis {
                applier.synchronize(EventApplier.CursorUpdate.EndOfBatch)
            }
            println("Replay finished in ${elapsedMs}ms (${eventCount} events).")

            // Nothing to re-seed here: appendEvent derives the next id from the
            // event log itself (max + 1), so id allocation has no projection-
            // resident state to restore. Dropping vote_data only wipes the
            // cursor (last_synced), which the replay above already reset — and a
            // wiped cursor is self-healing, it just triggers a full re-sync.
        } catch (e: Exception) {
            // Don't resume on failure — the event log is in a known-paused
            // state, but the projection is in an unknown state. Surface
            // recovery instructions and require an operator to inspect.
            Output.error(
                "Rebuild failed mid-stream: ${e.message}. Event log left PAUSED. " +
                    "Inspect vote_data, then re-run rebuild-projection. " +
                    "If the table is missing, the next run will recreate it from scratch.",
            )
        }

        println("Resuming event log...")
        eventLog.setPaused(false)

        Output.success("Projection rebuild complete.")
    }

    private suspend fun waitForTableGone(client: DynamoDbClient) {
        repeat(60) {
            try {
                client.describeTable(DescribeTableRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                })
                // Still exists — wait and retry.
            } catch (_: ResourceNotFoundException) {
                return
            }
            delay(1000)
        }
        error("vote_data did not become NOT FOUND within 60s of delete-table call")
    }

    private suspend fun waitForTableActive(client: DynamoDbClient) {
        repeat(60) {
            val response = client.describeTable(DescribeTableRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
            })
            val status = response.table?.tableStatus
            if (status == TableStatus.Active) {
                // Also verify every GSI is ACTIVE — table can be ACTIVE
                // with GSIs still CREATING in the rare case where GSI
                // status lags table status.
                val gsis = response.table?.globalSecondaryIndexes ?: emptyList()
                val pending = gsis.filter { it.indexStatus != aws.sdk.kotlin.services.dynamodb.model.IndexStatus.Active }
                if (pending.isEmpty()) return
            }
            delay(1000)
        }
        error("vote_data did not become ACTIVE within 60s of create-table call")
    }

    private fun requireConfirmation(prod: Boolean) {
        if (prod) {
            print("Type 'rebuild production' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "rebuild production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
        } else {
            print("Rebuild local DynamoDB projection? Type 'y' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "y" && typed != "yes") Output.error("Aborted.")
        }
    }
}
