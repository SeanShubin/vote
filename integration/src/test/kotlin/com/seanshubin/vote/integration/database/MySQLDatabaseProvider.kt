package com.seanshubin.vote.integration.database

import com.seanshubin.vote.backend.repository.MySqlCommandModel
import com.seanshubin.vote.backend.repository.MySqlEventLog
import com.seanshubin.vote.backend.repository.MySqlQueryModel
import com.seanshubin.vote.backend.repository.QueryLoaderFromResource
import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel
import kotlinx.serialization.json.Json
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager

class MySQLDatabaseProvider : DatabaseProvider {
    override val name = "MySQL"

    private val container: MySQLContainer<*> = MySQLContainer("mysql:8.0")
        .withDatabaseName("vote")
        .withUsername("vote")
        .withPassword("vote")
        .apply { start() }

    private val connection: Connection = DriverManager.getConnection(
        container.jdbcUrl,
        container.username,
        container.password
    )

    init {
        // Apply schema
        val schema = javaClass.classLoader
            .getResourceAsStream("database/schema.sql")!!
            .bufferedReader()
            .use { it.readText() }

        connection.createStatement().use { stmt ->
            schema.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { sql -> stmt.addBatch(sql) }
            stmt.executeBatch()
        }
    }

    private val queryLoader = QueryLoaderFromResource()
    private val json = Json { ignoreUnknownKeys = true }

    override val eventLog: EventLog = MySqlEventLog(connection, queryLoader, json)
    override val commandModel: CommandModel = MySqlCommandModel(connection, queryLoader, json)
    override val queryModel: QueryModel = MySqlQueryModel(connection, queryLoader, json)

    override fun close() {
        connection.close()
        container.stop()
    }

    override fun toString() = name
}
