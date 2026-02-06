package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*

object DynamoDbSchema {
    const val EVENT_LOG_TABLE = "vote_event_log"
    const val SYNC_STATE_TABLE = "vote_sync_state"
    const val USERS_TABLE = "vote_users"
    const val ELECTIONS_TABLE = "vote_elections"
    const val CANDIDATES_TABLE = "vote_candidates"
    const val ELIGIBLE_VOTERS_TABLE = "vote_eligible_voters"
    const val BALLOTS_TABLE = "vote_ballots"

    suspend fun createTables(dynamoDb: DynamoDbClient) {
        createEventLogTable(dynamoDb)
        createSyncStateTable(dynamoDb)
        createUsersTable(dynamoDb)
        createElectionsTable(dynamoDb)
        createCandidatesTable(dynamoDb)
        createEligibleVotersTable(dynamoDb)
        createBallotsTable(dynamoDb)
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

    private suspend fun createSyncStateTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = SYNC_STATE_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "id"
                    keyType = KeyType.Hash
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "id"
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    private suspend fun createUsersTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = USERS_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "name"
                    keyType = KeyType.Hash
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "email"
                    attributeType = ScalarAttributeType.S
                }
            )
            globalSecondaryIndexes = listOf(
                GlobalSecondaryIndex {
                    indexName = "email-index"
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "email"
                            keyType = KeyType.Hash
                        }
                    )
                    projection = Projection {
                        projectionType = ProjectionType.All
                    }
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    private suspend fun createElectionsTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = ELECTIONS_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "election_name"
                    keyType = KeyType.Hash
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "election_name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "owner_name"
                    attributeType = ScalarAttributeType.S
                }
            )
            globalSecondaryIndexes = listOf(
                GlobalSecondaryIndex {
                    indexName = "owner-index"
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "owner_name"
                            keyType = KeyType.Hash
                        }
                    )
                    projection = Projection {
                        projectionType = ProjectionType.All
                    }
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    private suspend fun createCandidatesTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = CANDIDATES_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "election_name"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "candidate_name"
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "election_name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "candidate_name"
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    private suspend fun createEligibleVotersTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = ELIGIBLE_VOTERS_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "election_name"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "voter_name"
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "election_name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "voter_name"
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    private suspend fun createBallotsTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = BALLOTS_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "election_name"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "voter_name"
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "election_name"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "voter_name"
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
        })
    }
}
