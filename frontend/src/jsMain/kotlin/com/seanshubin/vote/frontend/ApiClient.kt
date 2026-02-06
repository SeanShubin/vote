package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.json

object ApiClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "http://localhost:8080"  // Backend server, not frontend server

    suspend fun register(userName: String, email: String, password: String): Tokens {
        val request = RegisterRequest(userName, email, password)
        return post<RegisterRequest, Tokens>("/register", request)
    }

    suspend fun authenticate(userName: String, password: String): Tokens {
        val request = AuthenticateRequest(userName, password)
        return post<AuthenticateRequest, Tokens>("/authenticate", request)
    }

    suspend fun listElections(authToken: String): List<ElectionSummary> {
        return getWithAuth("/elections", authToken)
    }

    suspend fun createElection(authToken: String, electionName: String): String {
        val userName = extractUserName(authToken)
        val request = AddElectionRequest(userName, electionName)
        postWithAuth<AddElectionRequest, Unit>("/election", request, authToken)
        return electionName
    }

    suspend fun getElection(authToken: String, electionName: String): ElectionSummary {
        return getWithAuth("/election/${encodeURIComponent(electionName)}", authToken)
    }

    suspend fun setCandidates(authToken: String, electionName: String, candidates: List<String>) {
        val request = SetCandidatesRequest(candidates)
        putWithAuth<SetCandidatesRequest, Unit>("/election/${encodeURIComponent(electionName)}/candidates", request, authToken)
    }

    suspend fun listCandidates(authToken: String, electionName: String): List<String> {
        return getWithAuth("/election/${encodeURIComponent(electionName)}/candidates", authToken)
    }

    suspend fun setEligibleVoters(authToken: String, electionName: String, voters: List<String>) {
        val request = SetEligibleVotersRequest(voters)
        putWithAuth<SetEligibleVotersRequest, Unit>("/election/${encodeURIComponent(electionName)}/eligibility", request, authToken)
    }

    suspend fun launchElection(authToken: String, electionName: String) {
        val request = LaunchElectionRequest(allowEdit = true)
        postWithAuth<LaunchElectionRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/launch",
            request,
            authToken
        )
    }

    suspend fun castBallot(authToken: String, electionName: String, rankings: List<Ranking>): String {
        val request = CastBallotRequest(electionName, rankings)
        return postWithAuth("/election/${encodeURIComponent(electionName)}/ballot", request, authToken)
    }

    suspend fun getTally(authToken: String, electionName: String): Tally {
        return getWithAuth("/election/${encodeURIComponent(electionName)}/tally", authToken)
    }

    private suspend inline fun <reified TReq, reified TRes> post(path: String, body: TReq): TRes {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "POST",
            headers = json(
                "Content-Type" to "application/json"
            ),
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
            body = json.encodeToString(body)
        )).await()
        return handleResponse(response)
    }

    private suspend inline fun <reified T> getWithAuth(path: String, authToken: String): T {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "GET",
            headers = json(
                "Authorization" to "Bearer $authToken"
            )
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

    private fun fetch(url: String, init: RequestInit): kotlin.js.Promise<Response> {
        return window.fetch(url, init)
    }

    private fun encodeURIComponent(str: String): String {
        return js("encodeURIComponent")(str) as String
    }

    private fun extractUserName(authToken: String): String {
        val tokenData = json.decodeFromString<kotlinx.serialization.json.JsonObject>(authToken)
        return tokenData["userName"]?.toString()?.removeSurrounding("\"")
            ?: throw IllegalArgumentException("Invalid auth token")
    }

    fun logErrorToServer(error: Throwable) {
        try {
            val errorRequest = ClientErrorRequest(
                message = error.message ?: error.toString(),
                stackTrace = error.stackTraceToString(),
                url = window.location.href,
                userAgent = window.navigator.userAgent,
                timestamp = js("new Date().toISOString()") as String
            )

            // Use basic fetch without awaiting - fire and forget
            window.fetch("$baseUrl/log-client-error", RequestInit(
                method = "POST",
                headers = json(
                    "Content-Type" to "application/json"
                ),
                body = json.encodeToString(errorRequest)
            ))
        } catch (loggingError: Throwable) {
            // If logging fails, at least show it in console
            console.error("Failed to log error to server:", loggingError)
            console.error("Original error:", error)
        }
    }
}

class ApiException(message: String) : Exception(message)
