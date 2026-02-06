package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.backend.http.SimpleHttpHandler
import com.seanshubin.vote.backend.integration.ProductionIntegrations
import com.seanshubin.vote.backend.repository.*
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.contract.Service
import kotlinx.serialization.json.Json
import org.eclipse.jetty.server.Server
import java.sql.Connection
import java.sql.DriverManager

sealed class DatabaseConfig {
    data object InMemory : DatabaseConfig()
    data class MySql(
        val url: String = "jdbc:mysql://localhost:3306/vote",
        val user: String = "vote",
        val password: String = "vote"
    ) : DatabaseConfig()
}

class ApplicationDependencies(
    private val port: Int,
    private val databaseConfig: DatabaseConfig = DatabaseConfig.InMemory,
    private val integrations: Integrations = ProductionIntegrations
) {
    private val json = Json { prettyPrint = true }

    private val connection: Connection? = when (databaseConfig) {
        is DatabaseConfig.InMemory -> null
        is DatabaseConfig.MySql -> {
            Class.forName("com.mysql.cj.jdbc.Driver")
            DriverManager.getConnection(
                databaseConfig.url,
                databaseConfig.user,
                databaseConfig.password
            )
        }
    }

    private data class RepositorySet(
        val eventLog: EventLog,
        val commandModel: CommandModel,
        val queryModel: QueryModel
    )

    private val repositories: RepositorySet = when (databaseConfig) {
        is DatabaseConfig.InMemory -> {
            val eventLog = InMemoryEventLog()
            val sharedData = InMemoryData()
            val commandModel = InMemoryCommandModel(sharedData)
            val queryModel = InMemoryQueryModel(sharedData)
            RepositorySet(eventLog, commandModel, queryModel)
        }
        is DatabaseConfig.MySql -> {
            val eventLog = MySqlEventLog(connection!!, json)
            val commandModel = MySqlCommandModel(connection, json)
            val queryModel = MySqlQueryModel(connection, json)
            RepositorySet(eventLog, commandModel, queryModel)
        }
    }

    private val eventLog: EventLog = repositories.eventLog
    private val commandModel: CommandModel = repositories.commandModel
    private val queryModel: QueryModel = repositories.queryModel

    private val service: Service = ServiceImpl(
        integrations = integrations,
        eventLog = eventLog,
        commandModel = commandModel,
        queryModel = queryModel
    )

    private val httpHandler = SimpleHttpHandler(service, json)
    private val server = Server(port)

    init {
        server.handler = httpHandler
    }

    fun start() {
        println("Starting server on port $port...")
        server.start()
        println("Server started. Visit http://localhost:$port/health")
        server.join()
    }

    fun stop() {
        server.stop()
        connection?.close()
    }
}
