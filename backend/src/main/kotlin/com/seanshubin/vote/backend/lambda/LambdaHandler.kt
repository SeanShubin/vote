package com.seanshubin.vote.backend.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import com.seanshubin.vote.backend.auth.DiscordConfigProvider
import com.seanshubin.vote.backend.auth.DiscordOAuthClient
import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.SsmDiscordConfigProvider
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.dependencies.Bootstrap
import com.seanshubin.vote.backend.dependencies.ConnectionFactory
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.backend.dependencies.DynamoDbStartup
import com.seanshubin.vote.backend.dependencies.RepositoryFactory
import com.seanshubin.vote.backend.http.HttpRequest
import com.seanshubin.vote.backend.http.SetCookie
import com.seanshubin.vote.backend.integration.ProductionIntegrations
import com.seanshubin.vote.backend.router.RequestRouter
import com.seanshubin.vote.backend.service.DynamoToRelational
import com.seanshubin.vote.backend.service.EventApplier
import com.seanshubin.vote.backend.service.ServiceImpl
import kotlinx.serialization.json.Json
import java.net.http.HttpClient

/**
 * Lambda entry point — translates between API Gateway HTTPv2 events and
 * the runtime-agnostic [RequestRouter]. Mirrors what [SimpleHttpHandler]
 * does for Jetty.
 *
 * The router is built once in the static initializer so SnapStart's
 * checkpoint captures fully-initialized state. Subsequent invocations
 * (cold-restored or warm) reuse the same instance.
 *
 * Path layout: CloudFront proxies `/api/...` to API Gateway, so the
 * path we receive is `/api/health`, `/api/election/Foo`, etc. We strip
 * the `/api` prefix before routing so the router stays runtime-agnostic.
 */
class LambdaHandler : RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    override fun handleRequest(event: APIGatewayV2HTTPEvent, context: Context): APIGatewayV2HTTPResponse {
        // APIGW HTTP API v2 puts cookies in `event.cookies` rather than
        // a Cookie header. Reassemble into the standard header so RequestRouter's
        // cookie reader works the same as in the Jetty path.
        val headers = (event.headers ?: emptyMap()).toMutableMap()
        event.cookies?.takeIf { it.isNotEmpty() }?.let { cookies ->
            headers["Cookie"] = cookies.joinToString("; ")
        }

        // Path arrives as /api/health (CloudFront → APIGW → Lambda) — RequestRouter
        // strips the /api prefix uniformly for both Lambda and Jetty entry points.
        // rawPath excludes the query string; APIGW v2 delivers it separately as
        // rawQueryString — needed for the Discord OAuth callback's code/state.
        val request = HttpRequest(
            method = event.requestContext?.http?.method ?: "GET",
            target = event.rawPath ?: "/",
            rawHeaders = headers,
            body = event.body ?: "",
            queryString = event.rawQueryString ?: "",
        )
        val response = router.route(request)

        val builder = APIGatewayV2HTTPResponse.builder()
            .withStatusCode(response.status)
            // Merge contentType in with any extra headers set on the response
            // (e.g., Location for the Discord OAuth callback's 302 redirect).
            // Extra headers go last so a route that genuinely needs to override
            // Content-Type can do so by including it in `headers`.
            .withHeaders(mapOf("Content-Type" to response.contentType) + response.headers)
            .withBody(response.body)

        if (response.setCookies.isNotEmpty()) {
            builder.withCookies(response.setCookies.map(SetCookie::render))
        }
        return builder.build()
    }

    companion object {
        // Single HttpClient shared across all warm Lambda invocations. SnapStart
        // captures it in the checkpoint, so cold-restored invocations reuse the
        // same connection-pooled instance rather than building a fresh client
        // per invocation.
        private val sharedHttpClient: HttpClient = HttpClient.newHttpClient()
        private val router: RequestRouter = buildRouter()

        private fun buildRouter(): RequestRouter {
            val integrations = ProductionIntegrations(emptyArray())
            val configuration = Bootstrap(integrations).parseLambdaConfiguration()
            val region = (configuration.databaseConfig as DatabaseConfig.DynamoDB).region

            val json = Json { prettyPrint = true }
            val connectionFactory = ConnectionFactory(configuration)
            val repositoryFactory = RepositoryFactory(configuration, json)

            val dynamoDbClient = connectionFactory.createDynamoDbClient()
            DynamoDbStartup(integrations).ensureTables(dynamoDbClient!!)
            val sqlConnection = connectionFactory.createSqlConnection()
            val repositories = repositoryFactory.createRepositories(sqlConnection, dynamoDbClient)

            val tokenEncoder = TokenEncoder(JwtCipher(configuration.jwtSecret))

            // Discord login is on only when CFN configures all four SSM
            // parameter names AND each parameter resolves to a non-blank
            // value at runtime. Anything else disables the feature and
            // ServiceImpl rejects Discord login attempts with UNSUPPORTED.
            val discordConfigProvider: DiscordConfigProvider =
                configuration.discordParameterNames?.let { names ->
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
                systemSettings = repositories.systemSettings,
                tokenEncoder = tokenEncoder,
                discordConfigProvider = discordConfigProvider,
                discordOAuthClient = DiscordOAuthClient(httpClient = sharedHttpClient),
                relationalProjection = DynamoToRelational(repositories.queryModel, repositories.eventLog),
                eventApplier = EventApplier(repositories.eventLog, repositories.commandModel, repositories.queryModel),
                // parseLambdaConfiguration hard-codes this false — the
                // dev-login bypass cannot exist in production.
                devLoginEnabled = configuration.devLoginEnabled,
            )

            return RequestRouter(
                service = service,
                json = json,
                tokenEncoder = tokenEncoder,
                refreshCookie = configuration.cookieConfig,
                frontendBaseUrl = configuration.frontendBaseUrl,
                notifications = integrations.notifications,
            )
        }
    }
}
