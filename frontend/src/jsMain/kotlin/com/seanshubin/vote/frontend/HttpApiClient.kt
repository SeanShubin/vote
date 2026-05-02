package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.js.json

/**
 * HTTP-based ApiClient. Owns the current session state — access token,
 * username, and role — set on register/authenticate/refresh and updated
 * transparently when a 401 triggers a silent refresh.
 *
 * Production: callers don't pass tokens around. They just call methods;
 * if no session is active, authenticated methods throw NotAuthenticatedException.
 *
 * Testing: pass [initialSession] in the constructor to simulate a logged-in
 * state without going through the register/authenticate dance.
 */
class HttpApiClient(
    private val baseUrl: String,
    private val fetch: (String, RequestInit) -> Promise<Response> =
        { url, init -> window.fetch(url, init) },
    initialSession: Session? = null,
) : ApiClient {
    private val json = Json { ignoreUnknownKeys = true }

    // RequestCredentials.INCLUDE didn't resolve in this Kotlin/JS version —
    // the underlying wire value is just the string "include", which is what
    // the browser's fetch API expects.
    private val credentialsInclude: RequestCredentials = "include".unsafeCast<RequestCredentials>()

    /** Current session state; null when not authenticated. */
    private var session: Session? = initialSession

    /** Snapshot of the current authenticated user. Useful for the SPA's UI display. */
    val currentSession: Session? get() = session

    data class Session(val accessToken: String, val userName: String, val role: Role)

    override suspend fun register(userName: String, email: String, password: String): AuthResponse {
        val request = RegisterRequest(userName, email, password)
        val auth = post<RegisterRequest, AuthResponse>("/register", request)
        session = Session(auth.accessToken, auth.userName, auth.role)
        return auth
    }

    override suspend fun authenticate(nameOrEmail: String, password: String): AuthResponse {
        val request = AuthenticateRequest(nameOrEmail, password)
        val auth = post<AuthenticateRequest, AuthResponse>("/authenticate", request)
        session = Session(auth.accessToken, auth.userName, auth.role)
        return auth
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
        if (response.status.toInt() == 401) {
            session = null
            return null
        }
        val auth = handleResponse<AuthResponse>(response)
        session = Session(auth.accessToken, auth.userName, auth.role)
        return auth
    }

    override suspend fun logout() {
        session = null
        fetch("$baseUrl/logout", RequestInit(
            method = "POST",
            headers = json("Content-Type" to "application/json"),
            credentials = credentialsInclude,
            body = "",
        )).await()
    }

    override suspend fun requestPasswordReset(nameOrEmail: String) {
        post<PasswordResetRequestRequest, Unit>(
            "/password-reset-request",
            PasswordResetRequestRequest(nameOrEmail),
        )
    }

    override suspend fun resetPassword(resetToken: String, newPassword: String) {
        post<PasswordResetRequest, Unit>(
            "/password-reset",
            PasswordResetRequest(resetToken, newPassword),
        )
    }

    override suspend fun listElections(): List<ElectionSummary> =
        getWithAuth("/elections")

    override suspend fun createElection(electionName: String, description: String): String {
        val userName = requireSession().userName
        val request = AddElectionRequest(userName, electionName, description)
        postWithAuth<AddElectionRequest, Unit>("/election", request)
        return electionName
    }

    override suspend fun getElection(electionName: String): ElectionDetail =
        getWithAuth("/election/${encodeURIComponent(electionName)}")

    override suspend fun setElectionDescription(electionName: String, description: String) {
        val request = SetDescriptionRequest(description)
        putWithAuth<SetDescriptionRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/description",
            request,
        )
    }

    override suspend fun setCandidates(electionName: String, candidates: List<String>) {
        val request = SetCandidatesRequest(candidates)
        putWithAuth<SetCandidatesRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/candidates",
            request,
        )
    }

    override suspend fun listCandidates(electionName: String): List<String> =
        getWithAuth("/election/${encodeURIComponent(electionName)}/candidates")

    override suspend fun setTiers(electionName: String, tiers: List<String>) {
        val request = SetTiersRequest(tiers)
        putWithAuth<SetTiersRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/tiers",
            request,
        )
    }

    override suspend fun listTiers(electionName: String): List<String> =
        getWithAuth("/election/${encodeURIComponent(electionName)}/tiers")

    override suspend fun castBallot(electionName: String, rankings: List<Ranking>): String {
        val voterName = requireSession().userName
        val request = CastBallotRequest(voterName, rankings)
        return postWithAuth("/election/${encodeURIComponent(electionName)}/ballot", request)
    }

    override suspend fun deleteMyBallot(electionName: String) {
        val voterName = requireSession().userName
        deleteWithAuth(
            "/election/${encodeURIComponent(electionName)}/ballot/${encodeURIComponent(voterName)}"
        )
    }

    override suspend fun getMyRankings(electionName: String): List<Ranking> {
        val voterName = requireSession().userName
        return getWithAuth(
            "/election/${encodeURIComponent(electionName)}/rankings/${encodeURIComponent(voterName)}"
        )
    }

    override suspend fun getTally(electionName: String): Tally =
        getWithAuth("/election/${encodeURIComponent(electionName)}/tally")

    override suspend fun deleteElection(electionName: String) {
        deleteWithAuth("/election/${encodeURIComponent(electionName)}")
    }

    override suspend fun removeUser(userName: String) {
        deleteWithAuth("/user/${encodeURIComponent(userName)}")
    }

    override suspend fun listUsers(): List<UserNameRole> =
        getWithAuth("/users")

    override suspend fun getUserActivity(): UserActivity =
        getWithAuth("/me/activity")

    override suspend fun setRole(userName: String, role: Role) {
        val request = SetRoleRequest(role)
        putWithAuth<SetRoleRequest, Unit>("/user/${encodeURIComponent(userName)}/role", request)
    }

    override suspend fun listTables(): List<String> =
        getWithAuth("/tables")

    override suspend fun tableData(tableName: String): TableData =
        getWithAuth("/table/${encodeURIComponent(tableName)}")

    override suspend fun listDebugTables(): List<String> =
        getWithAuth("/debug-tables")

    override suspend fun debugTableData(tableName: String): TableData =
        getWithAuth("/debug-table/${encodeURIComponent(tableName)}")

    override fun logErrorToServer(error: Throwable) {
        // CancellationException isn't a real error — it's how Compose tells an
        // in-flight coroutine that the user navigated away or the composable
        // was disposed. We RETHROW it (not just return) so that the surrounding
        // catch (Exception) blocks across every page exit early instead of
        // continuing to set state on a disposed composable. This is the
        // single-point structural fix that obviates having to add a manual
        // `catch (CancellationException) { throw e }` at every call site.
        if (error is CancellationException) throw error

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
                headers = json("Content-Type" to "application/json"),
                body = json.encodeToString(errorRequest)
            ))
        } catch (loggingError: Throwable) {
            console.error("Failed to log error to server:", loggingError)
            console.error("Original error:", error)
        }
    }

    // --- Auth helpers ---

    private fun requireSession(): Session =
        session ?: throw NotAuthenticatedException()

    /**
     * Fire an authenticated request. If the server returns 401 we attempt one
     * silent refresh via the HttpOnly refresh cookie and retry with the new
     * access token. If refresh fails, the 401 propagates so the SPA can show
     * the login screen.
     *
     * Why retry once and not loop: a single retry handles the common case
     * (10-minute access token expired but 30-day refresh cookie still valid).
     * If the retry also 401s, the cookie is gone — let it propagate.
     */
    private suspend fun fetchWithAutoRefresh(
        path: String,
        buildInit: (String) -> RequestInit,
    ): Response {
        val token = requireSession().accessToken
        val response = fetch("$baseUrl$path", buildInit(token)).await()
        if (response.status.toInt() != 401) return response

        // Note: refresh() updates `session` as a side effect on success.
        val refreshed = refresh() ?: return response
        return fetch("$baseUrl$path", buildInit(refreshed.accessToken)).await()
    }

    // --- Wire-format helpers ---

    private suspend inline fun <reified TReq, reified TRes> post(path: String, body: TReq): TRes {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "POST",
            headers = json("Content-Type" to "application/json"),
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
    ): TRes {
        // Serialize before the lambda so the non-inline fetchWithAutoRefresh
        // doesn't need TReq's reified type info inside its closure.
        val serialized = json.encodeToString(body)
        val response = fetchWithAutoRefresh(path) { token ->
            RequestInit(
                method = "POST",
                headers = json(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $token"
                ),
                credentials = credentialsInclude,
                body = serialized
            )
        }
        return handleResponse(response)
    }

    private suspend inline fun <reified TReq, reified TRes> putWithAuth(
        path: String,
        body: TReq,
    ): TRes {
        val serialized = json.encodeToString(body)
        val response = fetchWithAutoRefresh(path) { token ->
            RequestInit(
                method = "PUT",
                headers = json(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $token"
                ),
                credentials = credentialsInclude,
                body = serialized
            )
        }
        return handleResponse(response)
    }

    private suspend inline fun <reified T> getWithAuth(path: String): T {
        val response = fetchWithAutoRefresh(path) { token ->
            RequestInit(
                method = "GET",
                headers = json("Authorization" to "Bearer $token"),
                credentials = credentialsInclude,
            )
        }
        return handleResponse(response)
    }

    private suspend fun deleteWithAuth(path: String) {
        val response = fetchWithAutoRefresh(path) { token ->
            RequestInit(
                method = "DELETE",
                headers = json("Authorization" to "Bearer $token"),
                credentials = credentialsInclude,
            )
        }
        handleResponse<Unit>(response)
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
}

class ApiException(message: String) : Exception(message)

/** Thrown when an authenticated method is called without an active session. */
class NotAuthenticatedException : RuntimeException("No active session. Call register() or authenticate() first.")
