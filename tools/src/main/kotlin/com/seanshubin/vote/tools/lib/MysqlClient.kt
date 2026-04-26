package com.seanshubin.vote.tools.lib

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

object MysqlClient {
    const val HOST = "localhost"
    const val PORT = 3306
    const val DATABASE = "vote"
    const val USER = "vote"
    const val PASSWORD = "vote"

    fun connect(): Connection {
        // Force the driver to register so DriverManager finds it under shadowed classloaders
        Class.forName("com.mysql.cj.jdbc.Driver")
        val url = "jdbc:mysql://$HOST:$PORT/$DATABASE"
        return DriverManager.getConnection(url, USER, PASSWORD)
    }

    /**
     * Execute a query and return rows as a list of column→value maps.
     */
    fun query(sql: String): List<Map<String, String?>> {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    return rs.toRows()
                }
            }
        }
    }

    /**
     * Execute SQL that does not return a result set.
     */
    fun execute(sql: String) {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(sql)
            }
        }
    }

    /**
     * Execute multiple statements (separated by semicolons). Useful for applying schema files.
     */
    fun executeScript(script: String) {
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                script.split(Regex(";\\s*\\n"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("--") }
                    .forEach { stmt.execute(it) }
            }
        }
    }

    private fun ResultSet.toRows(): List<Map<String, String?>> {
        val md = metaData
        val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
        val rows = mutableListOf<Map<String, String?>>()
        while (next()) {
            rows.add(cols.associateWith { col -> getString(col) })
        }
        return rows
    }
}
