package com.seanshubin.vote.documentation

import java.sql.Connection
import java.sql.ResultSet

class SqlHtmlGenerator(private val connection: Connection) {
    fun generate(): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html>")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <title>MySQL Database Dump</title>")
        appendLine("  <style>")
        appendLine(css())
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <h1>MySQL Database Dump</h1>")
        appendLine("  <p class=\"description\">Complete database state after running the comprehensive scenario.</p>")
        appendLine()

        val tables = listOf(
            "event_log",
            "users",
            "elections",
            "candidates",
            "eligible_voters",
            "ballots",
            "sync_state"
        )

        for (tableName in tables) {
            appendLine("  <h2>Table: $tableName</h2>")
            appendLine(generateTable(tableName))
            appendLine()
        }

        appendLine("</body>")
        appendLine("</html>")
    }

    private fun generateTable(tableName: String): String = buildString {
        val statement = connection.createStatement()
        val resultSet = statement.executeQuery("SELECT * FROM $tableName")

        val metadata = resultSet.metaData
        val columnCount = metadata.columnCount
        val columnNames = (1..columnCount).map { metadata.getColumnName(it) }

        appendLine("  <table>")
        appendLine("    <thead>")
        appendLine("      <tr>")
        for (columnName in columnNames) {
            appendLine("        <th>$columnName</th>")
        }
        appendLine("      </tr>")
        appendLine("    </thead>")
        appendLine("    <tbody>")

        var rowCount = 0
        while (resultSet.next()) {
            rowCount++
            appendLine("      <tr>")
            for (i in 1..columnCount) {
                val value = resultSet.getString(i) ?: "<null>"
                val escaped = escapeHtml(value)
                appendLine("        <td>$escaped</td>")
            }
            appendLine("      </tr>")
        }

        if (rowCount == 0) {
            appendLine("      <tr>")
            appendLine("        <td colspan=\"$columnCount\" class=\"empty\">(empty)</td>")
            appendLine("      </tr>")
        }

        appendLine("    </tbody>")
        appendLine("  </table>")
        appendLine("  <p class=\"row-count\">$rowCount rows</p>")

        resultSet.close()
        statement.close()
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
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        h1 {
            color: #333;
            border-bottom: 3px solid #0066cc;
            padding-bottom: 10px;
        }
        h2 {
            color: #0066cc;
            margin-top: 40px;
            border-bottom: 2px solid #ccc;
            padding-bottom: 5px;
        }
        .description {
            color: #666;
            font-size: 14px;
            margin-bottom: 30px;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            background-color: white;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            margin-bottom: 10px;
        }
        th {
            background-color: #0066cc;
            color: white;
            padding: 12px;
            text-align: left;
            font-weight: 600;
            text-transform: uppercase;
            font-size: 12px;
            letter-spacing: 0.5px;
        }
        td {
            padding: 10px 12px;
            border-bottom: 1px solid #eee;
        }
        tr:hover {
            background-color: #f8f9fa;
        }
        .empty {
            text-align: center;
            color: #999;
            font-style: italic;
        }
        .row-count {
            color: #666;
            font-size: 12px;
            margin-top: 5px;
            font-style: italic;
        }
    """.trimIndent()
}
