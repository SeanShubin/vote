package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
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
            verifyCursorWithinLog(dynamoDbClient)
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
     * Fail closed if the projection cursor claims to have synced past the end of
     * the event log. Now that the log allocates its own ids (max + 1) and the
     * cursor is the only projection-resident pointer, the one invariant that
     * still matters is `last_synced <= max(event_id)`: the projection can lag the
     * log (a wiped cursor self-heals by replaying) but must never lead it. A
     * cursor ahead of the log means the log lost a tail event behind the cursor
     * — e.g. an operator delete-event without a matching rewind — so the
     * projection now references events that no longer exist. Refuse traffic
     * rather than serve a projection built on a phantom suffix.
     */
    private suspend fun verifyCursorWithinLog(dynamoDbClient: DynamoDbClient) {
        val lastSynced = DynamoDbSingleTableSchema.readLastSynced(dynamoDbClient)
        val maxEventId = DynamoDbSingleTableSchema.readMaxEventId(dynamoDbClient)
        if (lastSynced > maxEventId) {
            throw CursorAheadOfLogException(lastSynced, maxEventId)
        }
        integrations.emitLine(
            "cursor invariant verified (last_synced=$lastSynced <= max_event_id=$maxEventId)"
        )
    }
}

/**
 * Thrown by [DynamoDbStartup.ensureTables] when the projection cursor has
 * advanced past the end of the event log — the projection references events
 * that no longer exist (a tail event was removed without rewinding the cursor).
 * Carries both values so the log names the gap.
 */
class CursorAheadOfLogException(lastSynced: Long, maxEventId: Long) : RuntimeException(
    "projection cursor (last_synced=$lastSynced) is ahead of the event log " +
        "(max event_id=$maxEventId). The projection references events that no longer exist — " +
        "the log lost a tail event behind the cursor. Restore the missing event(s) or rewind " +
        "the cursor and rebuild, then retry.",
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
