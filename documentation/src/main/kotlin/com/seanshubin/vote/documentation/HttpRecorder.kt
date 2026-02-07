package com.seanshubin.vote.documentation

import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.integration.fake.TestIntegrations
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class HttpExchange(
    val method: String,
    val path: String,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: String?,
    val responseStatus: Int,
    val responseHeaders: Map<String, List<String>>,
    val responseBody: String
)

class HttpRecorder {
    private val exchanges = mutableListOf<HttpExchange>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val compactJson = Json { ignoreUnknownKeys = true; prettyPrint = false }  // For headers
    private lateinit var app: ApplicationDependencies
    private lateinit var httpClient: HttpClient
    private val port = 19876
    private val baseUrl = "http://localhost:$port"

    fun startServer() {
        app = ApplicationDependencies(
            port = port,
            databaseConfig = DatabaseConfig.InMemory,
            integrations = TestIntegrations()
        )
        app.startNonBlocking()
        httpClient = HttpClient.newBuilder().build()

        // Wait for server ready
        var ready = false
        for (i in 1..50) {
            try {
                val response = get("/health")
                if (response.statusCode() == 200) {
                    ready = true
                    break
                }
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        if (!ready) throw IllegalStateException("Server failed to start")
    }

    fun stopServer() {
        app.stop()
    }

    fun getExchanges(): List<HttpExchange> = exchanges.toList()

    fun get(path: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .GET()
            .apply { token?.let { header("Authorization", "Bearer ${compactJson.encodeToString(it)}") } }
            .build()
        return sendAndRecord("GET", path, request)
    }

    fun post(path: String, body: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .apply { token?.let { header("Authorization", "Bearer ${compactJson.encodeToString(it)}") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sendAndRecord("POST", path, request, body)
    }

    fun put(path: String, body: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${compactJson.encodeToString(token)}")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sendAndRecord("PUT", path, request, body)
    }

    fun delete(path: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer ${compactJson.encodeToString(token)}")
            .DELETE()
            .build()
        return sendAndRecord("DELETE", path, request)
    }

    private fun sendAndRecord(
        method: String,
        path: String,
        request: HttpRequest,
        body: String? = null
    ): HttpResponse<String> {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        exchanges.add(
            HttpExchange(
                method = method,
                path = path,
                requestHeaders = request.headers().map(),
                requestBody = body,
                responseStatus = response.statusCode(),
                responseHeaders = response.headers().map(),
                responseBody = response.body()
            )
        )

        return response
    }
}
