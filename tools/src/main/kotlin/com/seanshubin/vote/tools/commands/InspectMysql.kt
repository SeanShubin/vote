package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.seanshubin.vote.tools.lib.MysqlClient

class InspectMysqlAll : CliktCommand(name = "inspect-mysql-all") {
    override fun help(context: Context) = "Dump all MySQL tables."

    override fun run() {
        println("=========================================")
        println("MySQL Database Complete Dump")
        println("=========================================")
        println()

        val tables = listTables()
        if (tables.isEmpty()) {
            println("No tables found in ${MysqlClient.DATABASE}.")
            return
        }

        for (table in tables) {
            println("=========================================")
            println("Table: $table")
            println("=========================================")

            val countRows = MysqlClient.query("SELECT COUNT(*) AS cnt FROM `$table`")
            val count = countRows.firstOrNull()?.get("cnt")?.toIntOrNull() ?: 0
            println("Row count: $count")
            println()

            if (count > 0) {
                val rows = MysqlClient.query("SELECT * FROM `$table`")
                rows.forEachIndexed { i, row ->
                    println("--- Row ${i + 1} ---")
                    row.forEach { (k, v) -> println("  $k: ${v ?: "NULL"}") }
                }
            } else {
                println("(empty)")
            }
            println()
        }

        println("=========================================")
        println("Summary")
        println("=========================================")
        println()
        for (table in tables) {
            val countRows = MysqlClient.query("SELECT COUNT(*) AS cnt FROM `$table`")
            val count = countRows.firstOrNull()?.get("cnt")?.toIntOrNull() ?: 0
            println(String.format("%-25s %3d rows", "$table:", count))
        }
    }
}

class InspectMysqlRawQuery : CliktCommand(name = "inspect-mysql-raw-query") {
    override fun help(context: Context) = "Run an arbitrary SQL query against MySQL."
    val query: String? by argument(name = "QUERY").optional()

    override fun run() {
        val sql = query ?: run {
            println("Usage: scripts/dev inspect-mysql-raw-query \"<sql>\"")
            println()
            println("Examples:")
            println("  scripts/dev inspect-mysql-raw-query \"SELECT * FROM users\"")
            println("  scripts/dev inspect-mysql-raw-query \"SHOW TABLES\"")
            println("  scripts/dev inspect-mysql-raw-query \"DESCRIBE elections\"")
            return
        }

        println("=========================================")
        println("MySQL Raw Query")
        println("=========================================")
        println()
        println("Query: $sql")
        println()
        println("Result:")
        println()
        try {
            val rows = MysqlClient.query(sql)
            if (rows.isEmpty()) {
                println("(no rows)")
                return
            }
            val cols = rows.first().keys.toList()
            println(cols.joinToString("\t"))
            rows.forEach { row ->
                println(cols.joinToString("\t") { row[it] ?: "NULL" })
            }
        } catch (e: java.sql.SQLException) {
            // Some queries (DDL, etc.) don't return rows; fall back to execute()
            try {
                MysqlClient.execute(sql)
                println("OK")
            } catch (e2: Exception) {
                System.err.println("ERROR: ${e2.message}")
                kotlin.system.exitProcess(1)
            }
        }
    }
}

class InspectMysqlRawSchema : CliktCommand(name = "inspect-mysql-raw-schema") {
    override fun help(context: Context) = "Show MySQL DDL, indexes, and constraints."

    override fun run() {
        println("=========================================")
        println("MySQL Raw Schema (Mechanical View)")
        println("=========================================")
        println()

        val tables = listTables()
        if (tables.isEmpty()) {
            println("No tables found.")
            return
        }

        for (table in tables) {
            println("=========================================")
            println("Table: $table")
            println("=========================================")
            println()

            println("--- DESCRIBE $table ---")
            printRows(MysqlClient.query("DESCRIBE `$table`"))
            println()

            println("--- INDEXES on $table ---")
            printRows(MysqlClient.query("SHOW INDEXES FROM `$table`"))
            println()

            println("--- CREATE TABLE $table ---")
            val createRows = MysqlClient.query("SHOW CREATE TABLE `$table`")
            createRows.forEach { row ->
                row.forEach { (k, v) -> println("  $k:\n$v") }
            }
            println()
        }
    }
}

private fun listTables(): List<String> =
    MysqlClient.query("SHOW TABLES").mapNotNull { it.values.firstOrNull() }

private fun printRows(rows: List<Map<String, String?>>) {
    if (rows.isEmpty()) {
        println("(no rows)")
        return
    }
    val cols = rows.first().keys.toList()
    println(cols.joinToString("\t"))
    rows.forEach { row ->
        println(cols.joinToString("\t") { row[it] ?: "NULL" })
    }
}
