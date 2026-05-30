package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*

/**
 * Operator-controlled state that has no source of truth in the event log:
 * the pause flag and feature flags. Lives in its own table — physically
 * separated from [DynamoDbSingleTableSchema]'s event-derived projection —
 * so a nuke+restore of the projection can't accidentally clobber state
 * that can't be rebuilt from events.
 *
 * Single-PK key schema; every row stands alone. Pause is one row
 * ([EVENT_LOG_PAUSED_PK]); each feature flag is one row keyed by
 * [featureFlagPK].
 */
object DynamoDbOperatorStateSchema {
    const val TABLE = "vote_operator_state"

    const val EVENT_LOG_PAUSED_PK = "EVENT_LOG_PAUSED"
    private const val FEATURE_FLAG_PK_PREFIX = "FEATURE_FLAG#"

    fun featureFlagPK(flagName: String) = "$FEATURE_FLAG_PK_PREFIX$flagName"

    suspend fun createTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "PK"
                    keyType = KeyType.Hash
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "PK"
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
            // Operator state (pause flag, feature flags) has no event-log source
            // of truth, so it can't be rebuilt — protect it like the event log.
            deletionProtectionEnabled = true
        })
        DynamoDbSingleTableSchema.enablePointInTimeRecovery(dynamoDb, TABLE)
    }
}
