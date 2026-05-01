package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BatchWriteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteRequest
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking

class NukeDynamodb : CliktCommand(name = "nuke-dynamodb") {
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()

    override fun help(context: Context) =
        "Wipe every item from vote_data and vote_event_log. Tables themselves are not deleted."

    override fun run() = runBlocking {
        val target = DynamoClient.describe(prod)
        Output.banner("Wiping data from $target")

        val mainCount: Int
        val eventCount: Int
        DynamoClient.createFor(prod).use { client ->
            mainCount = countTable(client, DynamoClient.TABLE_DATA)
            eventCount = countTable(client, DynamoClient.TABLE_EVENT_LOG)
        }
        println("Items to delete:")
        println("  ${DynamoClient.TABLE_DATA}:      $mainCount")
        println("  ${DynamoClient.TABLE_EVENT_LOG}: $eventCount")

        if (mainCount == 0 && eventCount == 0) {
            Output.success("Both tables are already empty. Nothing to do.")
            return@runBlocking
        }

        if (!yes) {
            requireConfirmation(prod)
        }

        DynamoClient.createFor(prod).use { client ->
            val mainDeleted = wipeMainTable(client)
            println("Deleted $mainDeleted item(s) from ${DynamoClient.TABLE_DATA}.")
            val eventDeleted = wipeEventLog(client)
            println("Deleted $eventDeleted item(s) from ${DynamoClient.TABLE_EVENT_LOG}.")
        }

        Output.success("Wipe complete.")
    }

    private fun requireConfirmation(prod: Boolean) {
        if (prod) {
            print("Type 'nuke production' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "nuke production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
        } else {
            print("Wipe local DynamoDB? Type 'y' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "y" && typed != "yes") Output.error("Aborted.")
        }
    }
}

internal suspend fun countTable(client: DynamoDbClient, table: String): Int {
    return try {
        var count = 0
        var startKey: Map<String, AttributeValue>? = null
        do {
            val response = client.scan(ScanRequest {
                tableName = table
                exclusiveStartKey = startKey
                select = aws.sdk.kotlin.services.dynamodb.model.Select.Count
            })
            count += response.count ?: 0
            startKey = response.lastEvaluatedKey
        } while (startKey != null)
        count
    } catch (_: ResourceNotFoundException) {
        0
    }
}

internal suspend fun wipeMainTable(client: DynamoDbClient): Int {
    var deleted = 0
    while (true) {
        val response = client.scan(ScanRequest {
            tableName = DynamoClient.TABLE_DATA
            projectionExpression = "PK, SK"
            limit = 1000
        })
        val items = response.items ?: emptyList()
        if (items.isEmpty()) break
        items.chunked(25).forEach { batch ->
            val requests = batch.map { item ->
                WriteRequest {
                    deleteRequest = DeleteRequest {
                        key = mapOf(
                            "PK" to (item["PK"] ?: error("scan returned row without PK")),
                            "SK" to (item["SK"] ?: error("scan returned row without SK")),
                        )
                    }
                }
            }
            executeBatchDelete(client, DynamoClient.TABLE_DATA, requests)
        }
        deleted += items.size
        if (response.lastEvaluatedKey == null) break
    }
    return deleted
}

internal suspend fun wipeEventLog(client: DynamoDbClient): Int {
    var deleted = 0
    while (true) {
        val response = client.scan(ScanRequest {
            tableName = DynamoClient.TABLE_EVENT_LOG
            projectionExpression = "event_id"
            limit = 1000
        })
        val items = response.items ?: emptyList()
        if (items.isEmpty()) break
        items.chunked(25).forEach { batch ->
            val requests = batch.map { item ->
                WriteRequest {
                    deleteRequest = DeleteRequest {
                        key = mapOf(
                            "event_id" to (item["event_id"] ?: error("scan returned row without event_id")),
                        )
                    }
                }
            }
            executeBatchDelete(client, DynamoClient.TABLE_EVENT_LOG, requests)
        }
        deleted += items.size
        if (response.lastEvaluatedKey == null) break
    }
    return deleted
}

private suspend fun executeBatchDelete(
    client: DynamoDbClient,
    table: String,
    requests: List<WriteRequest>,
) {
    var pending: Map<String, List<WriteRequest>>? = mapOf(table to requests)
    while (!pending.isNullOrEmpty()) {
        val response = client.batchWriteItem(BatchWriteItemRequest {
            requestItems = pending
        })
        pending = response.unprocessedItems
    }
}
