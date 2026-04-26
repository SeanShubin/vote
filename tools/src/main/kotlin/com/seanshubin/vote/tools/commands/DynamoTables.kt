package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.sdk.kotlin.services.dynamodb.model.GlobalSecondaryIndex
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.Projection
import aws.sdk.kotlin.services.dynamodb.model.ProjectionType
import aws.sdk.kotlin.services.dynamodb.model.ResourceInUseException
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import com.seanshubin.vote.tools.lib.DynamoClient

object DynamoTables {

    suspend fun ensureCreated() {
        DynamoClient.create().use { client ->
            createMainTableIfMissing(client)
            createEventLogTableIfMissing(client)
        }
    }

    private suspend fun createMainTableIfMissing(client: aws.sdk.kotlin.services.dynamodb.DynamoDbClient) {
        try {
            client.createTable {
                tableName = DynamoClient.TABLE_DATA
                attributeDefinitions = listOf(
                    AttributeDefinition { attributeName = "PK"; attributeType = ScalarAttributeType.S },
                    AttributeDefinition { attributeName = "SK"; attributeType = ScalarAttributeType.S },
                    AttributeDefinition { attributeName = "GSI1PK"; attributeType = ScalarAttributeType.S },
                    AttributeDefinition { attributeName = "GSI1SK"; attributeType = ScalarAttributeType.S }
                )
                keySchema = listOf(
                    KeySchemaElement { attributeName = "PK"; keyType = KeyType.Hash },
                    KeySchemaElement { attributeName = "SK"; keyType = KeyType.Range }
                )
                globalSecondaryIndexes = listOf(
                    GlobalSecondaryIndex {
                        indexName = "email-index"
                        keySchema = listOf(
                            KeySchemaElement { attributeName = "GSI1PK"; keyType = KeyType.Hash },
                            KeySchemaElement { attributeName = "GSI1SK"; keyType = KeyType.Range }
                        )
                        projection = Projection { projectionType = ProjectionType.All }
                    }
                )
                billingMode = BillingMode.PayPerRequest
            }
        } catch (_: ResourceInUseException) {
            println("Note: ${DynamoClient.TABLE_DATA} table already exists")
        }
    }

    private suspend fun createEventLogTableIfMissing(client: aws.sdk.kotlin.services.dynamodb.DynamoDbClient) {
        try {
            client.createTable {
                tableName = DynamoClient.TABLE_EVENT_LOG
                attributeDefinitions = listOf(
                    AttributeDefinition { attributeName = "event_id"; attributeType = ScalarAttributeType.N }
                )
                keySchema = listOf(
                    KeySchemaElement { attributeName = "event_id"; keyType = KeyType.Hash }
                )
                billingMode = BillingMode.PayPerRequest
            }
        } catch (_: ResourceInUseException) {
            println("Note: ${DynamoClient.TABLE_EVENT_LOG} table already exists")
        }
    }
}
