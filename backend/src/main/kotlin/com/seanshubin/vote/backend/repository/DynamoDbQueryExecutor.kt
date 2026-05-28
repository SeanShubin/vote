package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ExecuteStatementRequest
import com.seanshubin.vote.contract.QueryExecutor
import com.seanshubin.vote.domain.TableData
import kotlinx.coroutines.runBlocking

/**
 * Ad-hoc PartiQL queries against DynamoDB for the admin debug page.
 *
 * Read-only by construction: statements must begin with SELECT. INSERT /
 * UPDATE / DELETE through PartiQL would bypass the event log and corrupt
 * the event-sourced state, so they are rejected outright rather than gated
 * by a per-action permission.
 *
 * Result mapping mirrors [DynamoDbRawTableScanner]: items are heterogeneous,
 * so we render the union of all attribute names as columns; items missing an
 * attribute show null. Paginates via nextToken; capped at [MAX_ROWS] so a
 * runaway scan can't OOM the Lambda.
 */
class DynamoDbQueryExecutor(
    private val dynamoDb: DynamoDbClient,
) : QueryExecutor {

    override fun dialect(): String = "PartiQL"

    override fun execute(query: String): TableData {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "Query is empty" }
        require(trimmed.take(SELECT.length).equals(SELECT, ignoreCase = true)) {
            "Only SELECT statements are allowed (writes would bypass the event log)"
        }

        val items = executeAll(trimmed)
        val allColumns = items.flatMap { it.keys }.toSet()
        val orderedColumns = allColumns.sorted()
        val rows = items.map { item ->
            orderedColumns.map { col -> item[col]?.let(::renderAttribute) }
        }
        return TableData(QUERY_RESULT_NAME, orderedColumns, rows)
    }

    private fun executeAll(statement: String): List<Map<String, AttributeValue>> = runBlocking {
        val collected = mutableListOf<Map<String, AttributeValue>>()
        var token: String? = null
        do {
            val response = dynamoDb.executeStatement(ExecuteStatementRequest {
                this.statement = statement
                this.nextToken = token
            })
            response.items?.let(collected::addAll)
            if (collected.size >= MAX_ROWS) break
            token = response.nextToken
        } while (token != null)
        if (collected.size > MAX_ROWS) collected.subList(0, MAX_ROWS) else collected
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

    companion object {
        private const val SELECT = "SELECT"
        private const val MAX_ROWS = 10_000
        private const val QUERY_RESULT_NAME = "query_result"
    }
}
