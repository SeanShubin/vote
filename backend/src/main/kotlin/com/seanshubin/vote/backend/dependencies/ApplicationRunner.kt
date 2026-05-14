package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.auth.DiscordConfigProvider
import com.seanshubin.vote.backend.auth.DiscordOAuthClient
import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.SsmDiscordConfigProvider
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.router.RequestRouter
import com.seanshubin.vote.backend.router.SimpleHttpHandler
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.Service
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
    // Cached so test harnesses that seed the event log directly (see
    // getEventLog()) can force the projection to catch up.
    private var service: Service? = null

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

        // Discord login is enabled only when all four SSM parameter names are
        // present (and the SSM lookup succeeds for each). Dev runs that don't
        // configure Discord get the no-op provider; ServiceImpl rejects
        // Discord login attempts with UNSUPPORTED in that case.
        val discordConfigProvider: DiscordConfigProvider =
            configuration.discordParameterNames?.let { names ->
                val region = (configuration.databaseConfig as? DatabaseConfig.DynamoDB)?.region
                    ?: "us-east-1"
                SsmDiscordConfigProvider(
                    clientIdParameterName = names.clientId,
                    clientSecretParameterName = names.clientSecret,
                    redirectUriParameterName = names.redirectUri,
                    guildIdParameterName = names.guildId,
                    region = region,
                )
            } ?: DiscordConfigProvider { null }

        val service = ServiceImpl(
            integrations = integrations,
            eventLog = repositories.eventLog,
            commandModel = repositories.commandModel,
            queryModel = repositories.queryModel,
            rawTableScanner = repositories.rawTableScanner,
            tokenEncoder = tokenEncoder,
            discordConfigProvider = discordConfigProvider,
            discordOAuthClient = DiscordOAuthClient(),
        )
        this.service = service

        // Replay the event log into the projection so the scan below sees a
        // consistent picture of every existing election. Subsequent service
        // operations also synchronize, but the scan must run before any
        // request can hit the server.
        service.synchronize()
        CandidateTierCollisionCheck(repositories.queryModel).verify()

        val router = RequestRouter(
            service = service,
            json = json,
            tokenEncoder = tokenEncoder,
            refreshCookie = configuration.cookieConfig,
            frontendBaseUrl = configuration.frontendBaseUrl,
        )
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

    /**
     * Project any unsynced events into the read models. Service mutations
     * already self-synchronize; this is for test harnesses that append to
     * the event log directly via [getEventLog].
     */
    fun synchronize() = (service
        ?: error("ApplicationRunner not started — call run() or startNonBlocking() first"))
        .synchronize()
}
