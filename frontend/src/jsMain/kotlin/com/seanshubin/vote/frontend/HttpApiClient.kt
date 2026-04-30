package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.js.json

class HttpApiClient(
    private val baseUrl: String,
    // Injectable for wire-format tests; defaults to the browser's window.fetch.
    private val fetch: (String, RequestInit) -> Promise<Response> =
        { url, init -> window.fetch(url, init) },
) : ApiClient {
    private val json = Json { ignoreUnknownKeys = true }

    // RequestCredentials.INCLUDE didn't resolve in this Kotlin/JS version —
    // the underlying wire value is just the string "include", which is what
    // the browser's fetch API expects.
    private val credentialsInclude: RequestCredentials = "include".unsafeCast<RequestCredentials>()

    override suspend fun register(userName: String, email: String, password: String): AuthResponse {
        val request = RegisterRequest(userName, email, password)
        return post<RegisterRequest, AuthResponse>("/register", request)
    }

    override suspend fun authenticate(userName: String, password: String): AuthResponse {
        val request = AuthenticateRequest(userName, password)
        return post<AuthenticateRequest, AuthResponse>("/authenticate", request)
    }

    /**
     * The browser auto-attaches the HttpOnly Refresh cookie. If the cookie
     * is missing/invalid/expired, the server returns 401 and we return null
     * so the caller can show the login screen.
     */
    override suspend fun refresh(): AuthResponse? {
        val response = fetch("$baseUrl/refresh", RequestInit(
            method = "POST",
            headers = json("Content-Type" to "application/json"),
            credentials = credentialsInclude,
            body = "",
        )).await()
        if (response.status.toInt() == 401) return null
        return handleResponse(response)
    }

    override suspend fun logout() {
        fetch("$baseUrl/logout", RequestInit(
            method = "POST",
            headers = json("Content-Type" to "application/json"),
            credentials = credentialsInclude,
            body = "",
        )).await()
    }

    override suspend fun listElections(authToken: String): List<ElectionSummary> {
        return getWithAuth("/elections", authToken)
    }

    override suspend fun createElection(authToken: String, electionName: String): String {
        val userName = extractUserName(authToken)
        val request = AddElectionRequest(userName, electionName)
        postWithAuth<AddElectionRequest, Unit>("/election", request, authToken)
        return electionName
    }

    override suspend fun getElection(authToken: String, electionName: String): ElectionSummary {
        return getWithAuth("/election/${encodeURIComponent(electionName)}", authToken)
    }

    override suspend fun setCandidates(authToken: String, electionName: String, candidates: List<String>) {
        val request = SetCandidatesRequest(candidates)
        putWithAuth<SetCandidatesRequest, Unit>("/election/${encodeURIComponent(electionName)}/candidates", request, authToken)
    }

    override suspend fun listCandidates(authToken: String, electionName: String): List<String> {
        return getWithAuth("/election/${encodeURIComponent(electionName)}/candidates", authToken)
    }

    override suspend fun setEligibleVoters(authToken: String, electionName: String, voters: List<String>) {
        val request = SetEligibleVotersRequest(voters)
        putWithAuth<SetEligibleVotersRequest, Unit>("/election/${encodeURIComponent(electionName)}/eligibility", request, authToken)
    }

    override suspend fun launchElection(authToken: String, electionName: String) {
        val request = LaunchElectionRequest(allowEdit = true)
        postWithAuth<LaunchElectionRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/launch",
            request,
            authToken
        )
    }

    override suspend fun castBallot(authToken: String, electionName: String, rankings: List<Ranking>): String {
        val voterName = extractUserName(authToken)
        val request = CastBallotRequest(voterName, rankings)
        return postWithAuth("/election/${encodeURIComponent(electionName)}/ballot", request, authToken)
    }

    override suspend fun getTally(authToken: String, electionName: String): Tally {
        return getWithAuth("/election/${encodeURIComponent(electionName)}/tally", authToken)
    }

    override suspend fun listTables(authToken: String): List<String> {
        return getWithAuth("/tables", authToken)
    }

    override suspend fun tableData(authToken: String, tableName: String): TableData {
        return getWithAuth("/table/${encodeURIComponent(tableName)}", authToken)
    }

    override suspend fun listDebugTables(authToken: String): List<String> {
        return getWithAuth("/debug-tables", authToken)
    }

    override suspend fun debugTableData(authToken: String, tableName: String): TableData {
        return getWithAuth("/debug-table/${encodeURIComponent(tableName)}", authToken)
    }

    override fun logErrorToServer(error: Throwable) {
        try {
            val errorRequest = ClientErrorRequest(
                message = error.message ?: error.toString(),
                stackTrace = error.stackTraceToString(),
                url = window.location.href,
                userAgent = window.navigator.userAgent,
                timestamp = js("new Date().toISOString()") as String
            )

            window.fetch("$baseUrl/log-client-error", RequestInit(
                method = "POST",
                headers = json(
                    "Content-Type" to "application/json"
                ),
                body = json.encodeToString(errorRequest)
            ))
        } catch (loggingError: Throwable) {
            console.error("Failed to log error to server:", loggingError)
            console.error("Original error:", error)
        }
    }

    private suspend inline fun <reified TReq, reified TRes> post(path: String, body: TReq): TRes {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "POST",
            headers = json(
                "Content-Type" to "application/json"
            ),
            // Server may return Set-Cookie (for refresh token) — credentials:include
            // is required on register/authenticate so the browser stores the cookie.
            credentials = credentialsInclude,
            body = json.encodeToString(body)
        )).await()
        return handleResponse(response)
    }

    private suspend inline fun <reified TReq, reified TRes> postWithAuth(
        path: String,
        body: TReq,
        authToken: String
    ): TRes {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "POST",
            headers = json(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $authToken"
            ),
            credentials = credentialsInclude,
            body = json.encodeToString(body)
        )).await()
        return handleResponse(response)
    }

    private suspend inline fun <reified TReq, reified TRes> putWithAuth(
        path: String,
        body: TReq,
        authToken: String
    ): TRes {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "PUT",
            headers = json(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $authToken"
            ),
            credentials = credentialsInclude,
            body = json.encodeToString(body)
        )).await()
        return handleResponse(response)
    }

    private suspend inline fun <reified T> getWithAuth(path: String, authToken: String): T {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "GET",
            headers = json(
                "Authorization" to "Bearer $authToken"
            ),
            credentials = credentialsInclude,
        )).await()
        return handleResponse(response)
    }

    private suspend inline fun <reified T> handleResponse(response: Response): T {
        val text = response.text().await()

        if (!response.ok) {
            val error = try {
                json.decodeFromString<ErrorResponse>(text)
            } catch (e: Exception) {
                ErrorResponse("HTTP ${response.status}: $text")
            }
            throw ApiException(error.error)
        }

        return if (text.isEmpty() || text == "{}") {
            Unit as T
        } else {
            json.decodeFromString(text)
        }
    }

    private fun encodeURIComponent(str: String): String {
        return js("encodeURIComponent")(str) as String
    }

    /**
     * Extract the userName claim from a signed JWT's payload. The signature
     * is verified server-side; clients can read claims without a secret.
     */
    private fun extractUserName(jwt: String): String {
        val parts = jwt.split(".")
        require(parts.size == 3) { "Invalid JWT (expected 3 segments, got ${parts.size})" }
        // base64url → base64 (regular)
        val b64 = parts[1].replace('-', '+').replace('_', '/')
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        val decoded = js("atob")(padded) as String
        val obj = json.decodeFromString<JsonObject>(decoded)
        return obj["userName"]?.toString()?.removeSurrounding("\"")
            ?: throw IllegalArgumentException("JWT missing userName claim")
    }
}

class ApiException(message: String) : Exception(message)
