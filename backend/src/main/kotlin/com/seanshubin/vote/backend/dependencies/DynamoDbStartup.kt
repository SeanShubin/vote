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
 * The CI deploy workflow runs `vote-dev rebuild-projection --prod` after
 * every CFN deploy, so a healthy deploy gets the table reconciled before
 * Lambda traffic resumes. Lambda's check is the safety net that catches
 * deploys that bypassed CI (or for which the post-deploy step failed).
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
}

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
