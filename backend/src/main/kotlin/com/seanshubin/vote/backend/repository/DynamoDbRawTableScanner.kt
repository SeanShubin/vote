package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import com.seanshubin.vote.contract.RawTableScanner
import com.seanshubin.vote.domain.TableData
import kotlinx.coroutines.runBlocking

/**
 * Admin-only raw scan over the two physical DynamoDB tables.
 *
 * Items in `vote_data` are heterogeneous (single-table design with type-prefixed
 * keys), so we render the union of all attribute names as columns — items that
 * don't have a given attribute show null. Acceptable for an admin view; not
 * intended for hot-path use.
 */
class DynamoDbRawTableScanner(
    private val dynamoDb: DynamoDbClient,
) : RawTableScanner {

    override fun listRawTableNames(): List<String> =
        listOf(DynamoDbSingleTableSchema.MAIN_TABLE, DynamoDbSingleTableSchema.EVENT_LOG_TABLE)

    override fun scanRawTable(tableName: String): TableData {
        require(tableName in listRawTableNames()) { "Unknown raw table: $tableName" }
        val items = scanAll(tableName)

        // Column ordering: PK/SK (or event_id) first, then the rest alphabetically.
        val priority = when (tableName) {
            DynamoDbSingleTableSchema.MAIN_TABLE -> listOf("PK", "SK")
            DynamoDbSingleTableSchema.EVENT_LOG_TABLE -> listOf("event_id")
            else -> emptyList()
        }
        val allColumns = items.flatMap { it.keys }.toSet()
        val orderedColumns = priority.filter { it in allColumns } +
            (allColumns - priority.toSet()).sorted()

        val rows = items.map { item ->
            orderedColumns.map { col -> item[col]?.let(::renderAttribute) }
        }

        return TableData(tableName, orderedColumns, rows)
    }

    private fun scanAll(table: String): List<Map<String, AttributeValue>> = runBlocking {
        val collected = mutableListOf<Map<String, AttributeValue>>()
        var startKey: Map<String, AttributeValue>? = null
        do {
            val response = dynamoDb.scan(ScanRequest {
                tableName = table
                exclusiveStartKey = startKey
            })
            response.items?.let(collected::addAll)
            startKey = response.lastEvaluatedKey?.takeIf { it.isNotEmpty() }
        } while (startKey != null)
        collected
    }

    private fun renderAttribute(value: AttributeValue): String = when (value) {
        is AttributeValue.S -> value.value
        is AttributeValue.N -> value.value
        is AttributeValue.Bool -> value.value.toString()
        is AttributeValue.Null -> "null"
        is AttributeValue.B -> "<binary:${value.value.size} bytes>"
        is AttributeValue.Ss -> value.value.joinToString(prefix = "[", postfix = "]")
        is AttributeValue.Ns -> value.value.joinToString(prefix = "[", postfix = "]")
        is AttributeValue.Bs -> "<binary set:${value.value.size}>"
        is AttributeValue.L -> value.value.joinToString(prefix = "[", postfix = "]") { renderAttribute(it) }
        is AttributeValue.M -> value.value.entries.joinToString(prefix = "{", postfix = "}") {
            "${it.key}=${renderAttribute(it.value)}"
        }
        else -> value.toString()
    }
}
