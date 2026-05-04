package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.auth.CookieConfig
import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.http.SetCookie
import com.seanshubin.vote.backend.router.RequestRouter
import com.seanshubin.vote.backend.router.SimpleHttpHandler
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.QueryModel
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

        // Local-dev defaults: secure=false (HTTP), no domain (single-host),
        // path=/ (no /api prefix in Jetty since dev runs the backend directly).
        // The frontend runs on a separate port (3000) but the email link is
        // pasted into the browser, so localhost:3000 is the right reset target.
        val jwtSecret = System.getenv("JWT_SECRET") ?: "dev-jwt-secret-DO-NOT-USE-IN-PROD"
        val tokenEncoder = TokenEncoder(JwtCipher(jwtSecret))
        val frontendBaseUrl = System.getenv("FRONTEND_BASE_URL") ?: "http://localhost:3000"
        val cookieConfig = CookieConfig(
            secure = false,
            sameSite = SetCookie.SameSite.Lax,
            path = "/",
        )

        val service = ServiceImpl(
            integrations = integrations,
            eventLog = repositories.eventLog,
            commandModel = repositories.commandModel,
            queryModel = repositories.queryModel,
            rawTableScanner = repositories.rawTableScanner,
            tokenEncoder = tokenEncoder,
            frontendBaseUrl = frontendBaseUrl,
        )

        // Replay the event log into the projection so the scan below sees a
        // consistent picture of every existing election. Subsequent service
        // operations also synchronize, but the scan must run before any
        // request can hit the server.
        service.synchronize()
        scanForCandidateTierCollisions(repositories.queryModel)

        val router = RequestRouter(service, json, tokenEncoder, cookieConfig)
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
     * Block startup if any election in the event log has a candidate name
     * that collides (case-insensitively) with one of its tier names. The
     * detail pages classify each name as candidate-or-tier by membership
     * lookup, so an ambiguous name would render incorrectly. Old data
     * predating the cross-list validation could carry such a collision —
     * an admin must rename one of the colliding entries before the app
     * will come back up.
     */
    private fun scanForCandidateTierCollisions(queryModel: QueryModel) {
        val offenders = queryModel.listElections().mapNotNull { summary ->
            val candidates = queryModel.listCandidates(summary.electionName)
            val tiers = queryModel.listTiers(summary.electionName)
            val candidateKeys = candidates.map { it.lowercase() }.toSet()
            val tierKeys = tiers.map { it.lowercase() }.toSet()
            val collisions = candidateKeys.intersect(tierKeys)
            if (collisions.isEmpty()) null
            else summary.electionName to collisions.toList().sorted()
        }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (electionName, keys) ->
                "  - $electionName: ${keys.joinToString()}"
            }
            error(
                "Refusing to start: ${offenders.size} election(s) have names that " +
                    "appear as both a candidate and a tier (case-insensitive). " +
                    "Rename one side of each collision and restart.\n$report"
            )
        }
    }
}
