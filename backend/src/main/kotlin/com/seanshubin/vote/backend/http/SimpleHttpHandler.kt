package com.seanshubin.vote.backend.http

import com.seanshubin.vote.contract.Service
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

class SimpleHttpHandler(
    private val service: Service,
    private val json: Json
) : AbstractHandler() {

    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        baseRequest.isHandled = true
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"

        try {
            when (target) {
                "/health" -> {
                    val result = service.health()
                    response.status = HttpServletResponse.SC_OK
                    response.writer.write(json.encodeToString(mapOf("status" to result)))
                }
                else -> {
                    response.status = HttpServletResponse.SC_NOT_FOUND
                    response.writer.write(json.encodeToString(mapOf("error" to "Not found: $target")))
                }
            }
        } catch (e: Exception) {
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.writer.write(json.encodeToString(mapOf("error" to (e.message ?: "Unknown error"))))
        }
    }
}
