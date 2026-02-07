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

        val tables = getTableNames()

        for (tableName in tables) {
            appendLine("  <h2>Table: $tableName</h2>")
            appendLine(generateTable(tableName))
            appendLine()
        }

        appendLine("</body>")
        appendLine("</html>")
    }

    private fun getTableNames(): List<String> {
        val statement = connection.createStatement()
        val resultSet = statement.executeQuery("SHOW TABLES")
        val tables = mutableListOf<String>()
        while (resultSet.next()) {
            tables.add(resultSet.getString(1))
        }
        resultSet.close()
        statement.close()

        // Order tables by dependencies for better readability
        return orderTablesByDependencies(tables)
    }

    private fun orderTablesByDependencies(tables: List<String>): List<String> {
        // Domain tables: topologically sorted by foreign key dependencies
        // event_log: historical record (after domain state)
        // sync_state: operational metadata (last - rarely needed)

        val eventLog = "event_log"
        val syncState = "sync_state"
        val domainTables = tables.filter { it != eventLog && it != syncState }

        // Get foreign key dependencies
        val dependencies = getForeignKeyDependencies(domainTables)

        // Topological sort
        val sorted = topologicalSort(domainTables, dependencies)

        // Construct final order: domain tables, then event_log, then sync_state
        return buildList {
            addAll(sorted)
            if (tables.contains(eventLog)) add(eventLog)
            if (tables.contains(syncState)) add(syncState)
        }
    }

    private fun getForeignKeyDependencies(tables: List<String>): Map<String, Set<String>> {
        val dependencies = mutableMapOf<String, MutableSet<String>>()

        val sql = """
            SELECT
                TABLE_NAME,
                REFERENCED_TABLE_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = DATABASE()
              AND REFERENCED_TABLE_NAME IS NOT NULL
        """.trimIndent()

        val statement = connection.createStatement()
        val resultSet = statement.executeQuery(sql)

        while (resultSet.next()) {
            val table = resultSet.getString("TABLE_NAME")
            val referencedTable = resultSet.getString("REFERENCED_TABLE_NAME")

            if (table in tables && referencedTable in tables) {
                dependencies.getOrPut(table) { mutableSetOf() }.add(referencedTable)
            }
        }

        resultSet.close()
        statement.close()

        return dependencies
    }

    private fun topologicalSort(tables: List<String>, dependencies: Map<String, Set<String>>): List<String> {
        val sorted = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(table: String) {
            if (table in visited) return
            if (table in visiting) {
                // Cycle detected - fall back to original order for this table
                return
            }

            visiting.add(table)

            // Visit dependencies first (tables this table references)
            dependencies[table]?.forEach { dependency ->
                visit(dependency)
            }

            visiting.remove(table)
            visited.add(table)
            sorted.add(table)
        }

        tables.forEach { visit(it) }

        return sorted
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
