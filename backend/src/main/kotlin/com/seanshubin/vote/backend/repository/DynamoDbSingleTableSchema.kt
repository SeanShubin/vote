package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*

object DynamoDbSingleTableSchema {
    const val MAIN_TABLE = "vote_data"
    const val EVENT_LOG_TABLE = "vote_event_log"

    // Sparse GSI names — kept in lock-step with deploy/template.yaml.
    // Each is keyed on an attribute that only one entity type carries, so
    // the index contains exactly the items needed for its query path.
    const val DISCORD_ID_INDEX = "discord-id-index"
    const val VOTER_NAME_INDEX = "voter-name-index"
    const val OWNER_NAME_INDEX = "owner-name-index"
    const val MANAGER_USER_INDEX = "manager-user-index"

    /**
     * Constant partition key for every row in [EVENT_LOG_TABLE]. The log
     * lives in one partition with event_id as the sort key, so sync can
     * Query "PK = EVENT_LOG AND event_id > lastSynced" directly instead of
     * scanning the whole table. Append volume is far below the per-partition
     * write ceiling, so a single partition is fine here.
     */
    const val EVENT_LOG_PK = "EVENT_LOG"

    // Entity type prefixes
    const val USER_PREFIX = "USER#"
    const val ELECTION_PREFIX = "ELECTION#"
    const val CANDIDATE_PREFIX = "CANDIDATE#"
    const val MANAGER_PREFIX = "MANAGER#"
    const val BALLOT_PREFIX = "BALLOT#"
    /**
     * Sort-key prefix for candidate-note items, packing candidate and voter
     * into one SK so `begins_with(SK, NOTE#)` returns every note for an
     * election in one Query and `begins_with(SK, NOTE#<candidate>#)` returns
     * every note for one candidate. # is a safe delimiter — already in use
     * for the entity-prefix separator everywhere else.
     */
    const val NOTE_PREFIX = "NOTE#"
    const val METADATA_SK = "METADATA"
    const val SYNC_SK = "SYNC"

    suspend fun createTables(dynamoDb: DynamoDbClient) {
        // Each table is created independently so an already-existing one
        // doesn't abort creation of the others. This matters now that
        // vote_event_log is app-managed: in production vote_data always
        // exists, and a single createMainTable-then-createEventLogTable
        // sequence would throw on the first call and never reach — never
        // recreate — a missing event-log table.
        createIfMissing { createMainTable(dynamoDb) }
        createIfMissing { createEventLogTable(dynamoDb) }
    }

    private suspend fun createIfMissing(create: suspend () -> Unit) {
        try {
            create()
        } catch (e: ResourceInUseException) {
            // Table already exists — nothing to do.
        }
    }

    private suspend fun createMainTable(dynamoDb: DynamoDbClient) {
        // Four sparse GSIs back the user-keyed lookups that would otherwise
        // be table scans. See the deploy/template.yaml MainTable for the
        // production definition — this code path is what LocalStack
        // integration tests use, so the two must stay in lock-step.
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
                AttributeDefinition {
                    attributeName = "discord_id"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "voter_name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "owner_name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "user_name"
                    attributeType = ScalarAttributeType.S
                },
            )
            globalSecondaryIndexes = listOf(
                GlobalSecondaryIndex {
                    indexName = DISCORD_ID_INDEX
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "discord_id"
                            keyType = KeyType.Hash
                        },
                    )
                    projection = Projection { projectionType = ProjectionType.All }
                },
                GlobalSecondaryIndex {
                    indexName = VOTER_NAME_INDEX
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "voter_name"
                            keyType = KeyType.Hash
                        },
                        KeySchemaElement {
                            attributeName = "SK"
                            keyType = KeyType.Range
                        },
                    )
                    projection = Projection { projectionType = ProjectionType.All }
                },
                GlobalSecondaryIndex {
                    indexName = OWNER_NAME_INDEX
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "owner_name"
                            keyType = KeyType.Hash
                        },
                        KeySchemaElement {
                            attributeName = "SK"
                            keyType = KeyType.Range
                        },
                    )
                    projection = Projection { projectionType = ProjectionType.All }
                },
                GlobalSecondaryIndex {
                    indexName = MANAGER_USER_INDEX
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "user_name"
                            keyType = KeyType.Hash
                        },
                        KeySchemaElement {
                            attributeName = "SK"
                            keyType = KeyType.Range
                        },
                    )
                    projection = Projection { projectionType = ProjectionType.All }
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
                    attributeName = "PK"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "event_id"
                    keyType = KeyType.Range
                },
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "PK"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "event_id"
                    attributeType = ScalarAttributeType.N
                },
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    // Helper functions to build keys.
    // Every user-supplied identifier is lowercased in the key because names
    // in this app are case-insensitive for uniqueness and lookup — only
    // passwords are case-sensitive, and there are no user passwords (Discord
    // OAuth only). The display case lives in the item's "name" /
    // "election_name" / "candidate_name" / "voter_name" / "user_name"
    // attributes; the key is purely a canonicalized lookup form.
    fun userPK(userName: String) = "$USER_PREFIX${userName.lowercase()}"
    fun electionPK(electionName: String) = "$ELECTION_PREFIX${electionName.lowercase()}"
    fun candidateSK(candidateName: String) = "$CANDIDATE_PREFIX${candidateName.lowercase()}"
    fun managerSK(userName: String) = "$MANAGER_PREFIX${userName.lowercase()}"
    fun ballotSK(voterName: String) = "$BALLOT_PREFIX${voterName.lowercase()}"

    /** SK that packs the (candidate, voter) pair onto an election partition. */
    fun noteSK(candidateName: String, voterName: String) =
        "$NOTE_PREFIX${candidateName.lowercase()}#${voterName.lowercase()}"

    /** Prefix that selects every note on one candidate. */
    fun noteSKPrefixForCandidate(candidateName: String) =
        "$NOTE_PREFIX${candidateName.lowercase()}#"
}
