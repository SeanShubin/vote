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
 * username, and role — set on Discord OAuth callback / refresh and updated
 * transparently when a 401 triggers a silent refresh.
 *
 * Production: callers don't pass tokens around. They just call methods;
 * if no session is active, authenticated methods throw NotAuthenticatedException.
 *
 * Testing: pass [initialSession] in the constructor to simulate a logged-in
 * state without going through the Discord OAuth dance.
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

    override var onSessionLost: (() -> Unit)? = null

    data class Session(val accessToken: String, val userName: String, val role: Role)

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

    /**
     * Unauthenticated GET — no Authorization header, so it skips the
     * fetchWithAutoRefresh path entirely. The polling loop calls this on a
     * timer; keeping it off the auth path means a poll can never itself
     * trigger a token refresh or a session-lost cascade.
     */
    override suspend fun version(): Long {
        val response = fetch("$baseUrl/version", RequestInit(
            method = "GET",
            headers = json("Content-Type" to "application/json"),
        )).await()
        val body = handleResponse<Map<String, Long>>(response)
        return body["version"] ?: 0
    }

    override suspend fun isEventLogPaused(): Boolean {
        val response = fetch("$baseUrl/admin/event-log/status", RequestInit(
            method = "GET",
            headers = json("Content-Type" to "application/json"),
        )).await()
        val body = handleResponse<Map<String, Boolean>>(response)
        return body["paused"] ?: false
    }

    override suspend fun pauseEventLog() {
        postEmptyWithAuth("/admin/event-log/pause")
    }

    override suspend fun resumeEventLog() {
        postEmptyWithAuth("/admin/event-log/resume")
    }

    /**
     * POST with an empty body — the pause/resume endpoints don't take a
     * payload; the action is the URL. `postWithAuth` insists on a serializable
     * TReq, and `Unit` has no JSON serializer in kotlinx.serialization, so this
     * goes through `fetchWithAutoRefresh` directly.
     */
    private suspend fun postEmptyWithAuth(path: String) {
        val response = fetchWithAutoRefresh(path) { token ->
            RequestInit(
                method = "POST",
                headers = json(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $token"
                ),
                credentials = credentialsInclude,
                body = "",
            )
        }
        handleResponse<Unit>(response)
    }

    override suspend fun getMyUser(): UserNameEmail {
        val userName = requireSession().userName
        return getWithAuth("/user/${encodeURIComponent(userName)}")
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

    override suspend fun addCandidates(electionName: String, candidateNames: List<String>) {
        val request = AddCandidatesRequest(candidateNames)
        postWithAuth<AddCandidatesRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/candidate-add",
            request,
        )
    }

    override suspend fun removeCandidate(electionName: String, candidateName: String) {
        deleteWithAuth(
            "/election/${encodeURIComponent(electionName)}/candidate/${encodeURIComponent(candidateName)}"
        )
    }

    override suspend fun listCandidates(electionName: String): List<String> =
        getWithAuth("/election/${encodeURIComponent(electionName)}/candidates")

    override suspend fun renameCandidate(electionName: String, oldName: String, newName: String) {
        val request = RenameCandidateRequest(oldName, newName)
        postWithAuth<RenameCandidateRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/candidate-rename",
            request,
        )
    }

    override suspend fun candidateBallotCounts(electionName: String): Map<String, Int> =
        getWithAuth("/election/${encodeURIComponent(electionName)}/candidate-ballot-counts")

    override suspend fun setTiers(electionName: String, tiers: List<String>) {
        val request = SetTiersRequest(tiers)
        putWithAuth<SetTiersRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/tiers",
            request,
        )
    }

    override suspend fun listTiers(electionName: String): List<String> =
        getWithAuth("/election/${encodeURIComponent(electionName)}/tiers")

    override suspend fun renameTier(electionName: String, oldName: String, newName: String) {
        val request = RenameTierRequest(oldName, newName)
        postWithAuth<RenameTierRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/tier-rename",
            request,
        )
    }

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

    override suspend fun getTally(electionName: String): ElectionTally =
        getWithAuth("/election/${encodeURIComponent(electionName)}/tally")

    override suspend fun deleteElection(electionName: String) {
        deleteWithAuth("/election/${encodeURIComponent(electionName)}")
    }

    override suspend fun transferElectionOwnership(electionName: String, newOwnerName: String) {
        val request = TransferElectionOwnershipRequest(newOwnerName)
        putWithAuth<TransferElectionOwnershipRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/owner",
            request,
        )
    }

    override suspend fun addElectionManager(electionName: String, userName: String) {
        val request = AddElectionManagerRequest(userName)
        postWithAuth<AddElectionManagerRequest, Unit>(
            "/election/${encodeURIComponent(electionName)}/manager-add",
            request,
        )
    }

    override suspend fun removeElectionManager(electionName: String, userName: String) {
        deleteWithAuth(
            "/election/${encodeURIComponent(electionName)}/manager/${encodeURIComponent(userName)}"
        )
    }

    override suspend fun removeUser(userName: String) {
        deleteWithAuth("/user/${encodeURIComponent(userName)}")
    }

    override suspend fun listUsers(): List<UserNameRole> =
        getWithAuth("/users")

    override suspend fun listUserNames(): List<String> =
        getWithAuth("/users/names")

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

    override suspend fun discordLoginStartUrl(): String {
        // Unauthenticated POST — server sets the state cookie and returns the
        // authorize URL. The frontend then navigates the browser to that URL.
        val response = fetch("$baseUrl/auth/discord/start", RequestInit(
            method = "POST",
            headers = json("Content-Type" to "application/json"),
            credentials = credentialsInclude,
            body = "",
        )).await()
        val start = handleResponse<DiscordLoginStart>(response)
        return start.authorizeUrl
    }

    override suspend fun loginConfig(): LoginConfig {
        // Unauthenticated GET, like version() — read before any session exists.
        val response = fetch("$baseUrl/auth/config", RequestInit(
            method = "GET",
            headers = json("Content-Type" to "application/json"),
        )).await()
        return handleResponse(response)
    }

    override suspend fun devListUserNames(): List<String> {
        val response = fetch("$baseUrl/auth/dev/users", RequestInit(
            method = "GET",
            headers = json("Content-Type" to "application/json"),
        )).await()
        return handleResponse(response)
    }

    override suspend fun devLoginAsExisting(userName: String) {
        devLogin("/auth/dev/login", userName)
    }

    override suspend fun devCreateAndLogin(userName: String) {
        devLogin("/auth/dev/create", userName)
    }

    /**
     * Shared body for the two dev-login endpoints. Unauthenticated POST; the
     * server replies with an AuthResponse and sets the refresh cookie, exactly
     * like the Discord callback. We update the local session so the client is
     * immediately usable, though the login page reloads after this returns so
     * the normal cookie-based bootstrap runs.
     */
    private suspend fun devLogin(path: String, userName: String) {
        val response = fetch("$baseUrl$path", RequestInit(
            method = "POST",
            headers = json("Content-Type" to "application/json"),
            credentials = credentialsInclude,
            body = json.encodeToString(DevLoginRequest(userName)),
        )).await()
        val auth = handleResponse<AuthResponse>(response)
        session = Session(auth.accessToken, auth.userName, auth.role)
    }

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
            // Terminal sink: the server-logging path itself failed (network,
            // CORS, etc.). There's no further injectable channel — the
            // browser console is the last resort. This is the Humble I/O
            // Adapter pattern; everything testable has been extracted above.
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
     * access token. If refresh also fails, throw [SessionLostException] and
     * fire [onSessionLost] — exactly one place declares the session over.
     *
     * Why retry once and not loop: a single retry handles the common case
     * (10-minute access token expired but 30-day refresh cookie still valid).
     * If the retry also 401s, the cookie is gone or the user is gone — either
     * way, the only sensible UX is "log them out."
     */
    private suspend fun fetchWithAutoRefresh(
        path: String,
        buildInit: (String) -> RequestInit,
    ): Response {
        val token = requireSession().accessToken
        val response = fetch("$baseUrl$path", buildInit(token)).await()
        if (response.status.toInt() != 401) return response

        // Note: refresh() updates `session` (sets it to null on 401).
        val refreshed = refresh()
        if (refreshed == null) {
            onSessionLost?.invoke()
            throw SessionLostException()
        }
        return fetch("$baseUrl$path", buildInit(refreshed.accessToken)).await()
    }

    // --- Wire-format helpers ---

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

open class ApiException(message: String) : Exception(message)

/**
 * Raised by [HttpApiClient] when an authenticated request can't be recovered
 * via silent refresh (the server-side session is gone — refresh cookie expired
 * or the user was deleted). [HttpApiClient.onSessionLost] is invoked just
 * before this throws, so the SPA shell handles the navigation/state-clear; the
 * centralized async wrappers ([rememberFetchState], [rememberAsyncAction])
 * recognize this type and skip their normal error-display + server-log paths.
 */
class SessionLostException : ApiException("Session is no longer valid")

/** Thrown when an authenticated method is called without an active session. */
class NotAuthenticatedException : RuntimeException("No active session. Sign in with Discord first.")
