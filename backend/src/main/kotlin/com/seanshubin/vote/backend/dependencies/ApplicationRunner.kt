package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.router.RequestRouter
import com.seanshubin.vote.backend.router.SimpleHttpHandler
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
    private val dynamoDbStartup: DynamoDbStartup,
    private val json: Json,
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
        dynamoDbClient?.let(dynamoDbStartup::ensureTables)

        val repositories = repositoryFactory.createRepositories(sqlConnection, dynamoDbClient)
        this.repositories = repositories

        val tokenEncoder = TokenEncoder(JwtCipher(configuration.jwtSecret))

        val service = ServiceImpl(
            integrations = integrations,
            eventLog = repositories.eventLog,
            commandModel = repositories.commandModel,
            queryModel = repositories.queryModel,
            rawTableScanner = repositories.rawTableScanner,
            tokenEncoder = tokenEncoder,
            frontendBaseUrl = configuration.frontendBaseUrl,
        )

        // Replay the event log into the projection so the scan below sees a
        // consistent picture of every existing election. Subsequent service
        // operations also synchronize, but the scan must run before any
        // request can hit the server.
        service.synchronize()
        CandidateTierCollisionCheck(repositories.queryModel).verify()

        val router = RequestRouter(service, json, tokenEncoder, configuration.cookieConfig)
        val httpHandler = SimpleHttpHandler(router)
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
