package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.http.SimpleHttpHandler
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Integrations
import kotlinx.serialization.json.Json
import org.eclipse.jetty.server.Server
import java.sql.Connection

class ApplicationRunner(
    private val integrations: Integrations,
    private val configuration: Configuration,
    private val connectionFactory: ConnectionFactory,
    private val repositoryFactory: RepositoryFactory,
    private val json: Json
) {
    private var sqlConnection: Connection? = null
    private var dynamoDbClient: DynamoDbClient? = null
    private var server: Server? = null
    // Cache the repository set so getEventLog() returns the same instance the
    // running Service is writing to. Without this, repositoryFactory creates
    // fresh (empty) InMemory* instances on every call.
    private var repositories: RepositorySet? = null

    fun run() {
        bootstrap()
        server!!.start()
        integrations.emitLine("Server started. Visit http://localhost:${configuration.port}/health")
        server!!.join()
    }

    fun startNonBlocking() {
        bootstrap()
        server!!.start()
        integrations.emitLine("Server started. Visit http://localhost:${configuration.port}/health")
    }

    private fun bootstrap() {
        sqlConnection = connectionFactory.createSqlConnection()
        dynamoDbClient = connectionFactory.createDynamoDbClient()

        val repositories = repositoryFactory.createRepositories(sqlConnection, dynamoDbClient)
        this.repositories = repositories

        val service = ServiceImpl(
            integrations = integrations,
            eventLog = repositories.eventLog,
            commandModel = repositories.commandModel,
            queryModel = repositories.queryModel
        )

        val httpHandler = SimpleHttpHandler(service, json)
        server = Server(configuration.port)
        server!!.handler = httpHandler

        integrations.emitLine("Starting server on port ${configuration.port}...")
    }

    fun stop() {
        server?.stop()
        sqlConnection?.close()
        dynamoDbClient?.close()
    }

    fun getEventLog() = repositories?.eventLog
        ?: error("ApplicationRunner not started — call run() or startNonBlocking() first")
}
