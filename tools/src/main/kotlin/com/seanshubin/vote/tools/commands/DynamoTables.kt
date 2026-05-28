package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.listTables
import com.seanshubin.vote.backend.repository.DynamoDbOperatorStateSchema
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.tools.lib.DynamoClient

/**
 * Local-DynamoDB table bootstrap for the `db-setup-dynamodb` flow. The
 * actual create-table logic lives in [DynamoDbSingleTableSchema] and
 * [DynamoDbOperatorStateSchema] in the backend module — this object is a
 * thin tools-side wrapper so the local setup uses the same shape the
 * running backend expects. Keeping a separate hardcoded definition here
 * is how the `vote_data` shape drifted previously.
 */
object DynamoTables {

    // Probes the SDK round-trip, not just the TCP socket — Docker port-forwarding
    // accepts connections before the inner Java process has bound its listener,
    // which causes CreateTable to race against a half-initialized server.
    suspend fun awaitReady() {
        DynamoClient.create().use { client ->
            client.listTables { }
        }
    }

    suspend fun ensureCreated() {
        DynamoClient.create().use { client ->
            DynamoDbSingleTableSchema.createTables(client)
            DynamoDbOperatorStateSchema.createTable(client)
        }
    }
}
