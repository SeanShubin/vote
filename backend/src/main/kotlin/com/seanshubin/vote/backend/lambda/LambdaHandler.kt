package com.seanshubin.vote.backend.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse

/**
 * Placeholder Lambda handler — B.1 milestone. Real routing wired in B.4.
 * Used to validate the deploy pipeline end-to-end before porting business logic.
 */
class LambdaHandler : RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    override fun handleRequest(event: APIGatewayV2HTTPEvent, context: Context): APIGatewayV2HTTPResponse {
        val method = event.requestContext?.http?.method ?: "?"
        val path = event.rawPath ?: "?"
        val body = """{"status":"placeholder","method":"$method","path":"$path"}"""
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(200)
            .withHeaders(mapOf("Content-Type" to "application/json"))
            .withBody(body)
            .build()
    }
}
