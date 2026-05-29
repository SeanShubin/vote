package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import com.seanshubin.vote.backend.repository.DynamoDbOperatorStateSchema
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.contract.Integrations
import kotlinx.coroutines.runBlocking

/**
 * Creates the DynamoDB tables on startup if they don't exist, then verifies
 * vote_data's live shape against the shape declared in
 * [DynamoDbSingleTableSchema.expectedMainTableShape].
 *
 * If the live shape matches: startup proceeds.
 *
 * If the live shape differs (i.e. the running code expects GSIs/keys that
 * the table doesn't have): startup throws [ProjectionShapeMismatchException].
 * The Lambda's static initializer will surface this as a cold-init failure
 * (every invocation returns 500 until the table is reconciled by running
 * `vote-dev rebuild-projection --prod`). This is the deliberate fail-closed
 * design: better to refuse traffic than to serve queries against the wrong
 * shape and corrupt or fail-mysteriously.
 *
 * The CI deploy workflow runs `vote-dev rebuild-projection --prod` BEFORE
 * the CFN deploy step, so a healthy deploy gets the table reconciled
 * before CFN ever publishes the new Lambda version (whose stabilization
 * probe runs this same verify). Lambda's check is the safety net that
 * catches deploys that bypassed CI (or for which the pre-deploy
 * reconcile step failed).
 */
class DynamoDbStartup(
    private val integrations: Integrations,
) {
    fun ensureTables(dynamoDbClient: DynamoDbClient) {
        runBlocking {
            try {
                DynamoDbSingleTableSchema.createTables(dynamoDbClient)
                DynamoDbOperatorStateSchema.createTable(dynamoDbClient)
                integrations.emitLine("DynamoDB schemas created/verified")
            } catch (e: Exception) {
                integrations.emitLine("DynamoDB tables may already exist: ${e.message}")
            }

            verifyMainTableShape(dynamoDbClient)
            verifyEventCounterInvariant(dynamoDbClient)
        }
    }

    private suspend fun verifyMainTableShape(dynamoDbClient: DynamoDbClient) {
        val live = DynamoDbSingleTableSchema.readLiveMainTableShape(dynamoDbClient)
        val diff = DynamoDbSingleTableSchema.expectedMainTableShape.diffFrom(live)
        if (diff != null) {
            throw ProjectionShapeMismatchException(diff)
        }
        integrations.emitLine("vote_data shape verified against expected")
    }

    /**
     * Fail closed if the event-id counter has fallen behind the projection
     * cursor. The two are wiped together when rebuild-projection drops
     * vote_data; if a rebuild re-seeds the cursor (`last_synced`) but not the
     * counter (`next_event_id`), the counter restarts at 1 and every new event
     * is assigned an id at or below the cursor — silently overwriting old
     * events and being skipped by sync, with no error anywhere. That is the
     * worst failure mode (data loss that looks like success), so refuse traffic
     * rather than serve it. `next_event_id >= last_synced` must always hold:
     * appendEvent bumps the counter before sync advances the cursor.
     */
    private suspend fun verifyEventCounterInvariant(dynamoDbClient: DynamoDbClient) {
        val nextEventId = readMetadataNumber(
            dynamoDbClient,
            DynamoDbSingleTableSchema.EVENT_COUNTER_SK,
            DynamoDbSingleTableSchema.NEXT_EVENT_ID_ATTR,
        )
        val lastSynced = readMetadataNumber(
            dynamoDbClient,
            DynamoDbSingleTableSchema.SYNC_SK,
            DynamoDbSingleTableSchema.LAST_SYNCED_ATTR,
        )
        if (nextEventId < lastSynced) {
            throw EventCounterBehindCursorException(nextEventId, lastSynced)
        }
        integrations.emitLine(
            "event counter invariant verified (next_event_id=$nextEventId >= last_synced=$lastSynced)"
        )
    }

    /** Read a numeric attribute from a METADATA-partition singleton; 0 if absent. */
    private suspend fun readMetadataNumber(
        dynamoDbClient: DynamoDbClient,
        sortKey: String,
        attribute: String,
    ): Long {
        val response = dynamoDbClient.getItem(GetItemRequest {
            tableName = DynamoDbSingleTableSchema.MAIN_TABLE
            key = mapOf(
                "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                "SK" to AttributeValue.S(sortKey),
            )
            consistentRead = true
        })
        return response.item?.get(attribute)?.asN()?.toLong() ?: 0
    }
}

/**
 * Thrown by [DynamoDbStartup.ensureTables] when the event-id counter has
 * fallen behind the projection cursor — the signature of a rebuild that
 * re-seeded `last_synced` but not `next_event_id`. Carries both values so the
 * fix is obvious from the logs: re-seed EVENT_COUNTER to the max event id.
 */
class EventCounterBehindCursorException(nextEventId: Long, lastSynced: Long) : RuntimeException(
    "event-id counter (next_event_id=$nextEventId) is behind the projection cursor " +
        "(last_synced=$lastSynced). New events would be assigned ids at or below the cursor — " +
        "silently overwriting old events and being skipped by sync. Re-seed the counter to the " +
        "max event id (run rebuild-projection, which now seeds EVENT_COUNTER) and retry.",
)

/**
 * Thrown by [DynamoDbStartup.ensureTables] when the live vote_data shape
 * doesn't match the running code's expected shape. Carries the diff
 * message so CloudWatch logs name the specific mismatch (missing GSI,
 * extra attribute, etc.) without requiring a DDB console dive.
 */
class ProjectionShapeMismatchException(diff: String) : RuntimeException(
    "vote_data projection shape does not match the running code's expectations: $diff. " +
        "Reconcile by running `vote-dev rebuild-projection --prod` and then retry.",
)
