package com.seanshubin.vote.backend.router

import com.seanshubin.vote.backend.http.HttpRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

/**
 * Jetty servlet adapter — translates between Jetty servlet types and the
 * runtime-agnostic [HttpRequest] / [com.seanshubin.vote.backend.http.HttpResponse]
 * handled by [RequestRouter]. The Lambda handler is the parallel adapter for
 * API Gateway events.
 */
class SimpleHttpHandler(
    private val router: RequestRouter,
) : AbstractHandler() {

    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        baseRequest.isHandled = true

        // CORS for local dev (frontend on :3000 calling backend on :8080).
        // Production has the SPA + API on the same origin via CloudFront, so
        // these headers are belt-and-suspenders for dev only.
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:3000")
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        response.setHeader("Access-Control-Allow-Credentials", "true")
        response.setHeader("Access-Control-Max-Age", "3600")

        val headers = request.headerNames.toList().associateWith { request.getHeader(it) }
        val body = request.reader.use { it.readText() }

        val httpResponse = router.route(
            HttpRequest(
                method = request.method,
                target = target,
                rawHeaders = headers,
                body = body,
            )
        )

        response.contentType = httpResponse.contentType
        response.characterEncoding = "UTF-8"
        response.status = httpResponse.status
        // Set-Cookie can repeat; addHeader (not setHeader) preserves multiple values.
        for (cookie in httpResponse.setCookies) {
            response.addHeader("Set-Cookie", cookie.render())
        }
        response.writer.write(httpResponse.body)
    }
}
