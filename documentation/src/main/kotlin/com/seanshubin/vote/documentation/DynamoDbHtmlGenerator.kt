package com.seanshubin.vote.documentation

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import kotlinx.coroutines.runBlocking

class DynamoDbHtmlGenerator(private val client: DynamoDbClient) {
    fun generate(): String = runBlocking {
        buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html>")
            appendLine("<head>")
            appendLine("  <meta charset=\"UTF-8\">")
            appendLine("  <title>DynamoDB Single-Table Dump</title>")
            appendLine("  <style>")
            appendLine(css())
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <h1>DynamoDB Single-Table Design</h1>")
            appendLine("  <p class=\"description\">Complete DynamoDB table state using single-table design pattern.</p>")
            appendLine("  <p class=\"description\">All entities stored in one table using PK (Partition Key) and SK (Sort Key) pattern.</p>")
            appendLine()

            val dataItems = scanTable("vote_data")
            appendLine("  <h2>Table: vote_data (${dataItems.size} items)</h2>")
            appendLine(generateTable(dataItems))
            appendLine()

            val eventLogItems = scanTable("vote_event_log")
            appendLine("  <h2>Table: vote_event_log (${eventLogItems.size} items)</h2>")
            appendLine(generateEventLogTable(eventLogItems))

            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private suspend fun scanTable(tableName: String): List<Map<String, AttributeValue>> {
        val items = mutableListOf<Map<String, AttributeValue>>()
        var lastEvaluatedKey: Map<String, AttributeValue>? = null

        do {
            val request = ScanRequest {
                this.tableName = tableName
                exclusiveStartKey = lastEvaluatedKey
            }

            val response = client.scan(request)
            response.items?.let { items.addAll(it) }
            lastEvaluatedKey = response.lastEvaluatedKey
        } while (lastEvaluatedKey != null)

        // Sort by appropriate keys for each table
        return when (tableName) {
            "vote_data" -> items.sortedWith(compareBy(
                { it["PK"]?.asS() ?: "" },
                { it["SK"]?.asS() ?: "" }
            ))
            "vote_event_log" -> items.sortedBy { it["event_id"]?.asN()?.toLongOrNull() ?: 0L }
            else -> items
        }
    }

    private fun generateTable(items: List<Map<String, AttributeValue>>): String = buildString {
        if (items.isEmpty()) {
            appendLine("  <p class=\"empty\">(empty table)</p>")
            return@buildString
        }

        // Collect all attribute names
        val allAttributes = items
            .flatMap { it.keys }
            .distinct()
            .sorted()

        // Put PK and SK first
        val orderedAttributes = listOf("PK", "SK") + allAttributes.filter { it !in listOf("PK", "SK") }

        appendLine("  <table>")
        appendLine("    <thead>")
        appendLine("      <tr>")
        for (attr in orderedAttributes) {
            val cssClass = when (attr) {
                "PK" -> " class=\"pk\""
                "SK" -> " class=\"sk\""
                else -> ""
            }
            appendLine("        <th$cssClass>$attr</th>")
        }
        appendLine("      </tr>")
        appendLine("    </thead>")
        appendLine("    <tbody>")

        for (item in items) {
            appendLine("      <tr>")
            for (attr in orderedAttributes) {
                val value = item[attr]?.let { formatAttributeValue(it) } ?: ""
                val cssClass = when (attr) {
                    "PK" -> " class=\"pk\""
                    "SK" -> " class=\"sk\""
                    else -> ""
                }
                appendLine("        <td$cssClass>$value</td>")
            }
            appendLine("      </tr>")
        }

        appendLine("    </tbody>")
        appendLine("  </table>")
    }

    private fun generateEventLogTable(items: List<Map<String, AttributeValue>>): String = buildString {
        if (items.isEmpty()) {
            appendLine("  <p class=\"empty\">(empty table)</p>")
            return@buildString
        }

        // Collect all attribute names
        val allAttributes = items
            .flatMap { it.keys }
            .distinct()
            .sorted()

        // Put event_id first
        val orderedAttributes = listOf("event_id") + allAttributes.filter { it != "event_id" }

        appendLine("  <table>")
        appendLine("    <thead>")
        appendLine("      <tr>")
        for (attr in orderedAttributes) {
            val cssClass = if (attr == "event_id") " class=\"pk\"" else ""
            appendLine("        <th$cssClass>$attr</th>")
        }
        appendLine("      </tr>")
        appendLine("    </thead>")
        appendLine("    <tbody>")

        for (item in items) {
            appendLine("      <tr>")
            for (attr in orderedAttributes) {
                val value = item[attr]?.let { formatAttributeValue(it) } ?: ""
                val cssClass = if (attr == "event_id") " class=\"pk\"" else ""
                appendLine("        <td$cssClass>$value</td>")
            }
            appendLine("      </tr>")
        }

        appendLine("    </tbody>")
        appendLine("  </table>")
    }

    private fun formatAttributeValue(value: AttributeValue): String {
        return when (value) {
            is AttributeValue.S -> escapeHtml(value.value)
            is AttributeValue.N -> value.value
            is AttributeValue.Bool -> value.value.toString()
            is AttributeValue.Null -> "<null>"
            is AttributeValue.L -> "[${value.value.size} items]"
            is AttributeValue.M -> "{${value.value.size} attrs}"
            is AttributeValue.Ss -> value.value.joinToString(", ", "{", "}")
            is AttributeValue.Ns -> value.value.joinToString(", ", "{", "}")
            is AttributeValue.Bs -> "{${value.value.size} binary items}"
            else -> value.toString()
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun css() = """
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            max-width: 1600px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        h1 {
            color: #333;
            border-bottom: 3px solid #ff9900;
            padding-bottom: 10px;
        }
        h2 {
            color: #ff9900;
            margin-top: 40px;
            border-bottom: 2px solid #ccc;
            padding-bottom: 5px;
        }
        .description {
            color: #666;
            font-size: 14px;
            margin-bottom: 10px;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            background-color: white;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            margin-bottom: 10px;
            font-size: 13px;
        }
        th {
            background-color: #232f3e;
            color: white;
            padding: 12px 8px;
            text-align: left;
            font-weight: 600;
            font-size: 11px;
            letter-spacing: 0.5px;
            white-space: nowrap;
        }
        th.pk {
            background-color: #ff9900;
            color: #232f3e;
        }
        th.sk {
            background-color: #ffc266;
            color: #232f3e;
        }
        td {
            padding: 8px;
            border-bottom: 1px solid #eee;
            vertical-align: top;
        }
        td.pk {
            background-color: #fff5e6;
            font-weight: 600;
        }
        td.sk {
            background-color: #ffebcc;
            font-weight: 500;
        }
        tr:hover {
            background-color: #f8f9fa;
        }
        .empty {
            text-align: center;
            color: #999;
            font-style: italic;
            padding: 20px;
        }
    """.trimIndent()
}
