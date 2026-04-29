package com.seanshubin.vote.backend.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import com.seanshubin.vote.backend.dependencies.Configuration
import com.seanshubin.vote.backend.dependencies.ConnectionFactory
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.backend.dependencies.RepositoryFactory
import com.seanshubin.vote.backend.http.HttpRequest
import com.seanshubin.vote.backend.http.RequestRouter
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
 */
class LambdaHandler : RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    override fun handleRequest(event: APIGatewayV2HTTPEvent, context: Context): APIGatewayV2HTTPResponse {
        val request = HttpRequest(
            method = event.requestContext?.http?.method ?: "GET",
            target = event.rawPath ?: "/",
            rawHeaders = event.headers ?: emptyMap(),
            body = event.body ?: "",
        )
        val response = router.route(request)
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(response.status)
            .withHeaders(mapOf("Content-Type" to response.contentType))
            .withBody(response.body)
            .build()
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
            )
            return RequestRouter(service, json)
        }
    }
}
