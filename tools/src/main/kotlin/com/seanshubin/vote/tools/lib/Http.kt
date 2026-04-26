package com.seanshubin.vote.tools.lib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class Http(private val baseUrl: String) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    data class Response(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299

        fun asJson(): JsonElement = Json.parseToJsonElement(body)

        fun fieldOrNull(name: String): String? =
            runCatching { (asJson() as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.contentOrNull } }
                .getOrNull()
    }

    fun get(path: String, authToken: String? = null): Response {
        val request = baseRequest(path, authToken).GET().build()
        return send(request, "GET", path)
    }

    fun post(path: String, body: String? = null, authToken: String? = null): Response {
        val publisher = if (body != null) HttpRequest.BodyPublishers.ofString(body)
        else HttpRequest.BodyPublishers.noBody()
        val request = baseRequest(path, authToken)
            .header("Content-Type", "application/json")
            .POST(publisher)
            .build()
        return send(request, "POST", path)
    }

    fun put(path: String, body: String, authToken: String? = null): Response {
        val request = baseRequest(path, authToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return send(request, "PUT", path)
    }

    fun delete(path: String, authToken: String? = null): Response {
        val request = baseRequest(path, authToken).DELETE().build()
        return send(request, "DELETE", path)
    }

    fun ensureSuccess(method: String, path: String, response: Response, description: String? = null): Response {
        if (!response.ok) {
            val label = description ?: "$method $path"
            System.err.println("ERROR: $label returned HTTP ${response.status}")
            if (response.body.isNotBlank()) System.err.println(response.body.take(2000))
            kotlin.system.exitProcess(1)
        }
        return response
    }

    private fun baseRequest(path: String, authToken: String?): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
        if (authToken != null) {
            builder.header("Authorization", "Bearer $authToken")
        }
        return builder
    }

    private fun send(request: HttpRequest, method: String, path: String): Response {
        val resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return Response(resp.statusCode(), resp.body())
    }

    companion object {
        fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
