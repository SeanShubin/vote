package com.seanshubin.vote.backend.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import com.seanshubin.vote.backend.auth.CookieConfig
import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.dependencies.Configuration
import com.seanshubin.vote.backend.dependencies.ConnectionFactory
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.backend.dependencies.RepositoryFactory
import com.seanshubin.vote.backend.http.HttpRequest
import com.seanshubin.vote.backend.router.RequestRouter
import com.seanshubin.vote.backend.http.SetCookie
import com.seanshubin.vote.backend.integration.ProductionIntegrations
import com.seanshubin.vote.backend.service.ServiceImpl
import kotlinx.serialization.json.Json

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
        val request = HttpRequest(
            method = event.requestContext?.http?.method ?: "GET",
            target = event.rawPath ?: "/",
            rawHeaders = headers,
            body = event.body ?: "",
        )
        val response = router.route(request)

        val builder = APIGatewayV2HTTPResponse.builder()
            .withStatusCode(response.status)
            .withHeaders(mapOf("Content-Type" to response.contentType))
            .withBody(response.body)

        if (response.setCookies.isNotEmpty()) {
            builder.withCookies(response.setCookies.map(SetCookie::render))
        }
        return builder.build()
    }

    companion object {
        private val router: RequestRouter = buildRouter()

        private fun buildRouter(): RequestRouter {
            val region = System.getenv("AWS_REGION") ?: "us-east-1"
            val configuration = Configuration(
                port = 0,
                databaseConfig = DatabaseConfig.DynamoDB(endpoint = null, region = region),
            )
            val integrations = ProductionIntegrations(emptyArray())
            val json = Json { prettyPrint = true }
            val connectionFactory = ConnectionFactory(configuration)
            val repositoryFactory = RepositoryFactory(integrations, configuration, json)

            val sqlConnection = connectionFactory.createSqlConnection()
            val dynamoDbClient = connectionFactory.createDynamoDbClient()
            val repositories = repositoryFactory.createRepositories(sqlConnection, dynamoDbClient)

            val service = ServiceImpl(
                integrations = integrations,
                eventLog = repositories.eventLog,
                commandModel = repositories.commandModel,
                queryModel = repositories.queryModel,
                rawTableScanner = repositories.rawTableScanner,
            )

            // JWT secret comes from Lambda env (SecretsManager-backed). The
            // env-var fallback exists only to allow the static init to complete
            // when the secret isn't present — the resulting tokens won't verify
            // correctly and that's intentional.
            val jwtSecret = System.getenv("JWT_SECRET")
                ?: error("JWT_SECRET env var is required")
            val cipher = JwtCipher(jwtSecret)
            val tokenEncoder = TokenEncoder(cipher)
            val cookieConfig = CookieConfig(
                domain = System.getenv("COOKIE_DOMAIN"), // e.g. ".pairwisevote.com"
                secure = true,
                sameSite = SetCookie.SameSite.Lax,
                path = "/api",
            )

            return RequestRouter(service, json, tokenEncoder, cookieConfig)
        }
    }
}
