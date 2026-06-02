package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.IndexStatus
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import com.seanshubin.vote.backend.repository.DynamoDbEventLog
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableCommandModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableQueryModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.backend.service.EventApplier
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis

/**
 * Drop+recreate+replay rebuild of vote_data from vote_event_log. Used by
 * `rebuild-projection` (the explicit operator command) and by `delete-event`
 * (which chains here automatically so removing an event leaves the projection
 * in a consistent state without a manual follow-up step).
 *
 * The event log is paused for the duration so no writes can land mid-rebuild,
 * and resumed at the end on success. On failure the log is left PAUSED so an
 * operator inspects before traffic resumes — better to refuse writes than to
 * append onto a half-rebuilt projection.
 */
object ProjectionRebuilder {

    suspend fun rebuild(client: DynamoDbClient) {
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
            println("Replay finished in ${elapsedMs}ms ($eventCount events).")
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
                val pending = gsis.filter { it.indexStatus != IndexStatus.Active }
                if (pending.isEmpty()) return
            }
            delay(1000)
        }
        error("vote_data did not become ACTIVE within 60s of create-table call")
    }
}
