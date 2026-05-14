package com.seanshubin.vote.documentation

import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.integration.fake.TestIntegrations
import kotlinx.datetime.Instant
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

class HttpRecorder(private val documentationRecorder: DocumentationRecorder? = null) {
    private val exchanges = mutableListOf<HttpExchange>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    // Same dev secret used by ApplicationRunner — required for the in-process
    // server to verify JWTs we mint here.
    private val tokenEncoder = TokenEncoder(JwtCipher("dev-jwt-secret-DO-NOT-USE-IN-PROD"))
    private lateinit var runner: com.seanshubin.vote.backend.dependencies.ApplicationRunner
    private lateinit var httpClient: HttpClient
    private val port = 19876
    private val baseUrl = "http://localhost:$port"
    private var lastEventIndex = 0L

    fun startServer() {
        // Use staged dependency injection pattern
        val integrations = TestIntegrations()

        val configuration = com.seanshubin.vote.backend.dependencies.Configuration.forTesting(
            port = port,
            databaseConfig = DatabaseConfig.InMemory,
        )

        val appDeps = ApplicationDependencies(integrations, configuration)
        runner = appDeps.runner

        runner.startNonBlocking()
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
        runner.stop()
    }

    /**
     * Append [event] straight to the in-process server's event log and
     * project it. Test bootstrap only — there is no HTTP registration path
     * under Discord-only login, so seeded users must be written to the
     * server's own log rather than the test harness's separate in-memory
     * log. The explicit [ApplicationRunner.synchronize] is required because,
     * unlike a service mutation, a direct append triggers no auto-sync.
     */
    fun seedEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        runner.getEventLog().appendEvent(authority, whenHappened, event)
        runner.synchronize()
    }

    fun getExchanges(): List<HttpExchange> = exchanges.toList()

    fun get(path: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .GET()
            .apply { token?.let { header("Authorization", "Bearer ${tokenEncoder.encodeAccessToken(it)}") } }
            .build()
        return sendAndRecord("GET", path, request)
    }

    fun post(path: String, body: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .apply { token?.let { header("Authorization", "Bearer ${tokenEncoder.encodeAccessToken(it)}") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sendAndRecord("POST", path, request, body)
    }

    fun put(path: String, body: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${tokenEncoder.encodeAccessToken(token)}")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sendAndRecord("PUT", path, request, body)
    }

    fun delete(path: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer ${tokenEncoder.encodeAccessToken(token)}")
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

        val exchange = HttpExchange(
            method = method,
            path = path,
            requestHeaders = request.headers().map(),
            requestBody = body,
            responseStatus = response.statusCode(),
            responseHeaders = response.headers().map(),
            responseBody = response.body()
        )

        exchanges.add(exchange)

        // Record to documentation recorder if present
        documentationRecorder?.recordHttp(exchange)

        // Capture any new events that were generated
        captureNewEvents()

        return response
    }

    private fun captureNewEvents() {
        documentationRecorder?.let { recorder ->
            val eventLog = runner.getEventLog()
            val newEvents = eventLog.eventsToSync(lastEventIndex)
            for (envelope in newEvents) {
                recorder.recordEvent(envelope)
                lastEventIndex = envelope.eventId
            }
        }
    }
}
