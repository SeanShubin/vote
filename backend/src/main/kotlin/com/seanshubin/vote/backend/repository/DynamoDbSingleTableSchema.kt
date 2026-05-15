package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*

object DynamoDbSingleTableSchema {
    const val MAIN_TABLE = "vote_data"
    const val EVENT_LOG_TABLE = "vote_event_log"

    // Entity type prefixes
    const val USER_PREFIX = "USER#"
    const val ELECTION_PREFIX = "ELECTION#"
    const val CANDIDATE_PREFIX = "CANDIDATE#"
    const val MANAGER_PREFIX = "MANAGER#"
    const val BALLOT_PREFIX = "BALLOT#"
    const val METADATA_SK = "METADATA"
    const val SYNC_SK = "SYNC"

    suspend fun createTables(dynamoDb: DynamoDbClient) {
        createMainTable(dynamoDb)
        createEventLogTable(dynamoDb)
    }

    private suspend fun createMainTable(dynamoDb: DynamoDbClient) {
        // PK / SK only — the email-index GSI that supported password-flow
        // email lookups is gone now that Discord-only login removed every
        // by-email lookup path.
        dynamoDb.createTable(CreateTableRequest {
            tableName = MAIN_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "PK"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "SK"
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "PK"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "SK"
                    attributeType = ScalarAttributeType.S
                },
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    private suspend fun createEventLogTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = EVENT_LOG_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "event_id"
                    keyType = KeyType.Hash
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "event_id"
                    attributeType = ScalarAttributeType.N
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    // Helper functions to build keys.
    // User and ballot keys lowercase the username segment because username
    // uniqueness is case-insensitive — the lookup path is the canonical key.
    // The display case lives in the item's "name" / "voter_name" attributes.
    // Election and candidate names are case-sensitive and used as-is.
    fun userPK(userName: String) = "$USER_PREFIX${userName.lowercase()}"
    fun electionPK(electionName: String) = "$ELECTION_PREFIX$electionName"
    fun candidateSK(candidateName: String) = "$CANDIDATE_PREFIX$candidateName"
    // Manager keys lowercase the username segment for the same reason ballot
    // keys do — username uniqueness is case-insensitive; the display case
    // lives in the item's "user_name" attribute.
    fun managerSK(userName: String) = "$MANAGER_PREFIX${userName.lowercase()}"
    fun ballotSK(voterName: String) = "$BALLOT_PREFIX${voterName.lowercase()}"
}
