package com.seanshubin.vote.backend.router

import com.seanshubin.vote.backend.auth.CookieConfig
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.http.HttpRequest
import com.seanshubin.vote.backend.http.HttpResponse
import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.AddElectionRequest
import com.seanshubin.vote.contract.AuthResponse
import com.seanshubin.vote.contract.AuthenticateRequest
import com.seanshubin.vote.contract.CastBallotRequest
import com.seanshubin.vote.contract.ChangePasswordRequest
import com.seanshubin.vote.contract.ClientErrorRequest
import com.seanshubin.vote.contract.ErrorResponse
import com.seanshubin.vote.contract.PasswordResetRequest
import com.seanshubin.vote.contract.PasswordResetRequestRequest
import com.seanshubin.vote.contract.RegisterRequest
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.contract.SetCandidatesRequest
import com.seanshubin.vote.contract.SetRoleRequest
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.UserUpdates
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Pure HTTP routing — takes an [HttpRequest], returns an [HttpResponse].
 * No knowledge of Jetty, Lambda, or any specific runtime. The Jetty
 * [SimpleHttpHandler] and the Lambda handler are both thin adapters that
 * delegate here.
 *
 * Auth model: access tokens are signed JWTs in the Authorization header;
 * refresh tokens are signed JWTs in an HttpOnly cookie. Verification on
 * every request rejects tampered/expired tokens.
 */
class RequestRouter(
    private val service: Service,
    private val json: Json,
    private val tokenEncoder: TokenEncoder,
    private val refreshCookie: CookieConfig,
) {
    private val log = LoggerFactory.getLogger(RequestRouter::class.java)

    fun route(req: HttpRequest): HttpResponse {
        log.debug("${req.method} ${req.target}")

        if (req.method == "OPTIONS") {
            return HttpResponse(200, "")
        }

        // CloudFront proxies /api/... → API Gateway → Lambda. Frontend always
        // sends /api-prefixed paths; backend handles them either with or
        // without the prefix (so direct Jetty calls in tests/dev still work).
        val normalized = req.target.removePrefix("/api").ifEmpty { "/" }
        val normalizedReq = if (normalized == req.target) req else req.withTarget(normalized)

        return try {
            dispatch(normalizedReq)
        } catch (e: IllegalArgumentException) {
            log.warn("Bad request: ${req.method} ${req.target} - ${e.message}", e)
            errorResponse(400, e.message ?: "Bad request")
        } catch (e: ServiceException) {
            log.info("Service exception: ${req.method} ${req.target} - ${e.category}: ${e.message}")
            val status = when (e.category) {
                ServiceException.Category.UNAUTHORIZED -> 401
                ServiceException.Category.NOT_FOUND -> 404
                ServiceException.Category.CONFLICT -> 409
                ServiceException.Category.UNSUPPORTED -> 501
                ServiceException.Category.MALFORMED_JSON -> 400
            }
            errorResponse(status, e.message ?: "Service error")
        } catch (e: Exception) {
            log.error("Unhandled exception: ${req.method} ${req.target}", e)
            errorResponse(500, e.message ?: "Unknown error")
        }
    }

    private fun dispatch(req: HttpRequest): HttpResponse {
        val target = req.target
        val method = req.method
        return when {
            target == "/health" && method == "GET" -> handleHealth()
            target == "/sync" && method == "POST" -> handleSync()
            target == "/log-client-error" && method == "POST" -> handleLogClientError(req)
            target == "/register" && method == "POST" -> handleRegister(req)
            target == "/authenticate" && method == "POST" -> handleAuthenticate(req)
            target == "/refresh" && method == "POST" -> handleRefresh(req)
            target == "/logout" && method == "POST" -> handleLogout()
            target == "/password-reset-request" && method == "POST" -> handleRequestPasswordReset(req)
            target == "/password-reset" && method == "POST" -> handleResetPassword(req)
            target == "/users" && method == "GET" -> handleListUsers(req)
            target == "/users/count" && method == "GET" -> handleUserCount(req)
            target.matches(Regex("/user/[^/]+")) && method == "GET" -> handleGetUser(req)
            target.matches(Regex("/user/[^/]+")) && method == "PUT" -> handleUpdateUser(req)
            target.matches(Regex("/user/[^/]+")) && method == "DELETE" -> handleRemoveUser(req)
            target.matches(Regex("/user/[^/]+/role")) && method == "PUT" -> handleSetRole(req)
            target.matches(Regex("/user/[^/]+/password")) && method == "PUT" -> handleChangePassword(req)
            target.matches(Regex("/permissions/[^/]+")) && method == "GET" -> handlePermissionsForRole(req)
            target == "/tables" && method == "GET" -> handleListTables(req)
            target == "/tables/count" && method == "GET" -> handleTableCount(req)
            target == "/events/count" && method == "GET" -> handleEventCount(req)
            target == "/events" && method == "GET" -> handleEventData(req)
            target.matches(Regex("/table/[^/]+")) && method == "GET" -> handleTableData(req)
            target == "/debug-tables" && method == "GET" -> handleListDebugTables(req)
            target.matches(Regex("/debug-table/[^/]+")) && method == "GET" -> handleDebugTableData(req)
            target == "/election" && method == "POST" -> handleAddElection(req)
            target == "/elections" && method == "GET" -> handleListElections(req)
            target == "/elections/count" && method == "GET" -> handleElectionCount(req)
            target.matches(Regex("/election/[^/]+")) && method == "GET" -> handleGetElection(req)
            target.matches(Regex("/election/[^/]+")) && method == "DELETE" -> handleDeleteElection(req)
            target.matches(Regex("/election/[^/]+/candidates")) && method == "PUT" -> handleSetCandidates(req)
            target.matches(Regex("/election/[^/]+/candidates")) && method == "GET" -> handleListCandidates(req)
            target.matches(Regex("/election/[^/]+/ballot")) && method == "POST" -> handleCastBallot(req)
            target.matches(Regex("/election/[^/]+/ballot/[^/]+")) && method == "GET" -> handleGetBallot(req)
            target.matches(Regex("/election/[^/]+/rankings/[^/]+")) && method == "GET" -> handleListRankings(req)
            target.matches(Regex("/election/[^/]+/tally")) && method == "GET" -> handleTally(req)
            else -> errorResponse(404, "Not found: $method $target")
        }
    }

    private fun errorResponse(status: Int, message: String): HttpResponse =
        HttpResponse(status, json.encodeToString(ErrorResponse(message)))

    /** Verify and decode the bearer JWT; reject if missing/expired/tampered. */
    private fun extractAccessToken(req: HttpRequest): AccessToken {
        val authHeader = req.header("Authorization")
            ?: throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Missing Authorization header")
        if (!authHeader.startsWith("Bearer ")) {
            throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Invalid Authorization header format")
        }
        val jwt = authHeader.substring(7)
        return tokenEncoder.decodeAccessToken(jwt)
            ?: throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Access token invalid or expired"
            )
    }

    private fun extractUserName(target: String): String {
        val parts = target.split("/")
        return java.net.URLDecoder.decode(parts[2], "UTF-8")
    }

    private fun extractElectionName(target: String): String {
        val parts = target.split("/")
        return java.net.URLDecoder.decode(parts[2], "UTF-8")
    }

    private fun extractVoterOrUserName(target: String): String {
        val parts = target.split("/")
        return java.net.URLDecoder.decode(parts[4], "UTF-8")
    }

    /** Build a 200 response carrying [tokens] as both an AuthResponse body and a refresh-token cookie. */
    private fun authSuccess(tokens: Tokens): HttpResponse {
        val accessJwt = tokenEncoder.encodeAccessToken(tokens.accessToken)
        val refreshJwt = tokenEncoder.encodeRefreshToken(tokens.refreshToken)
        val body = AuthResponse(
            accessToken = accessJwt,
            userName = tokens.accessToken.userName,
            role = tokens.accessToken.role,
        )
        return HttpResponse(
            status = 200,
            body = json.encodeToString(body),
            setCookies = listOf(refreshCookie.makeSetCookie(refreshJwt)),
        )
    }

    /** Reads RFC-6265 cookie header; returns the value of [name] or null. */
    private fun readCookie(req: HttpRequest, name: String): String? {
        val header = req.header("Cookie") ?: return null
        return header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
    }

    private fun handleHealth(): HttpResponse {
        val result = service.health()
        return HttpResponse(200, json.encodeToString(mapOf("status" to result)))
    }

    private fun handleSync(): HttpResponse {
        service.synchronize()
        return HttpResponse(200, json.encodeToString(mapOf("status" to "synced")))
    }

    private fun handleLogClientError(req: HttpRequest): HttpResponse {
        val clientError = json.decodeFromString<ClientErrorRequest>(req.body)
        log.error(
            "CLIENT ERROR: ${clientError.message}\n" +
                "  URL: ${clientError.url}\n" +
                "  User-Agent: ${clientError.userAgent}\n" +
                "  Timestamp: ${clientError.timestamp}\n" +
                "  Stack trace: ${clientError.stackTrace ?: "none"}"
        )
        return HttpResponse(200, "{}")
    }

    private fun handleRegister(req: HttpRequest): HttpResponse {
        val registerRequest = json.decodeFromString<RegisterRequest>(req.body)
        val tokens = service.register(
            userName = registerRequest.userName,
            email = registerRequest.email,
            password = registerRequest.password,
        )
        return authSuccess(tokens)
    }

    private fun handleAuthenticate(req: HttpRequest): HttpResponse {
        val authRequest = json.decodeFromString<AuthenticateRequest>(req.body)
        val tokens = service.authenticate(
            nameOrEmail = authRequest.nameOrEmail,
            password = authRequest.password,
        )
        return authSuccess(tokens)
    }

    /**
     * Trade a refresh-cookie JWT for fresh access + refresh tokens.
     * Stateless: the JWT signature is the only credential — no server-side store.
     */
    private fun handleRefresh(req: HttpRequest): HttpResponse {
        val refreshJwt = readCookie(req, refreshCookie.name)
            ?: throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Missing refresh cookie")
        val refreshToken = tokenEncoder.decodeRefreshToken(refreshJwt)
            ?: throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Refresh token invalid or expired"
            )
        val tokens = service.refresh(refreshToken)
        return authSuccess(tokens)
    }

    /** Clears the refresh cookie. Stateless model — no server-side revocation. */
    private fun handleLogout(): HttpResponse {
        return HttpResponse(
            status = 200,
            body = json.encodeToString(mapOf("status" to "logged out")),
            setCookies = listOf(refreshCookie.makeClearCookie()),
        )
    }

    /** Unauthenticated — anyone can ask for a reset email; the email itself proves identity. */
    private fun handleRequestPasswordReset(req: HttpRequest): HttpResponse {
        val request = json.decodeFromString<PasswordResetRequestRequest>(req.body)
        service.requestPasswordReset(request.nameOrEmail)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "reset email sent")))
    }

    /** Unauthenticated — the reset token IS the credential. Bearer auth is irrelevant here. */
    private fun handleResetPassword(req: HttpRequest): HttpResponse {
        val request = json.decodeFromString<PasswordResetRequest>(req.body)
        service.resetPassword(request.resetToken, request.newPassword)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "password reset")))
    }

    private fun handleListUsers(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val users = service.listUsers(accessToken)
        return HttpResponse(200, json.encodeToString(users))
    }

    private fun handleUserCount(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val count = service.userCount(accessToken)
        return HttpResponse(200, json.encodeToString(mapOf("count" to count)))
    }

    private fun handleGetUser(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val userName = extractUserName(req.target)
        val user = service.getUser(accessToken, userName)
        return HttpResponse(200, json.encodeToString(user))
    }

    private fun handleUpdateUser(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val userName = extractUserName(req.target)
        val userUpdates = json.decodeFromString<UserUpdates>(req.body)
        service.updateUser(accessToken, userName, userUpdates)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "updated")))
    }

    private fun handleRemoveUser(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val userName = extractUserName(req.target)
        service.removeUser(accessToken, userName)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "removed")))
    }

    private fun handleSetRole(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val userName = extractUserName(req.target)
        val setRoleRequest = json.decodeFromString<SetRoleRequest>(req.body)
        service.setRole(accessToken, userName, setRoleRequest.role)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "role updated")))
    }

    private fun handleChangePassword(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val userName = extractUserName(req.target)
        val changePasswordRequest = json.decodeFromString<ChangePasswordRequest>(req.body)
        service.changePassword(accessToken, userName, changePasswordRequest.password)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "password changed")))
    }

    private fun handlePermissionsForRole(req: HttpRequest): HttpResponse {
        val parts = req.target.split("/")
        val role = Role.valueOf(parts[2])
        val permissions = service.permissionsForRole(role)
        return HttpResponse(200, json.encodeToString(permissions))
    }

    private fun handleListTables(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val tables = service.listTables(accessToken)
        return HttpResponse(200, json.encodeToString(tables))
    }

    private fun handleTableCount(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val count = service.tableCount(accessToken)
        return HttpResponse(200, json.encodeToString(mapOf("count" to count)))
    }

    private fun handleEventCount(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val count = service.eventCount(accessToken)
        return HttpResponse(200, json.encodeToString(mapOf("count" to count)))
    }

    private fun handleTableData(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val tableName = java.net.URLDecoder.decode(req.target.split("/").last(), "UTF-8")
        val tableData = service.tableData(accessToken, tableName)
        return HttpResponse(200, json.encodeToString(tableData))
    }

    private fun handleListDebugTables(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val tables = service.listDebugTables(accessToken)
        return HttpResponse(200, json.encodeToString(tables))
    }

    private fun handleDebugTableData(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val tableName = java.net.URLDecoder.decode(req.target.split("/").last(), "UTF-8")
        val tableData = service.debugTableData(accessToken, tableName)
        return HttpResponse(200, json.encodeToString(tableData))
    }

    private fun handleEventData(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val tableData = service.eventData(accessToken)
        return HttpResponse(200, json.encodeToString(tableData))
    }

    private fun handleAddElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val addElectionRequest = json.decodeFromString<AddElectionRequest>(req.body)
        service.addElection(accessToken, addElectionRequest.userName, addElectionRequest.electionName)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "election created")))
    }

    private fun handleListElections(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val elections = service.listElections(accessToken)
        return HttpResponse(200, json.encodeToString(elections))
    }

    private fun handleElectionCount(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val count = service.electionCount(accessToken)
        return HttpResponse(200, json.encodeToString(mapOf("count" to count)))
    }

    private fun handleGetElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val election = service.getElection(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(election))
    }

    private fun handleDeleteElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        service.deleteElection(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "election deleted")))
    }

    private fun handleSetCandidates(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val setCandidatesRequest = json.decodeFromString<SetCandidatesRequest>(req.body)
        service.setCandidates(accessToken, electionName, setCandidatesRequest.candidateNames)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "candidates updated")))
    }

    private fun handleListCandidates(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val candidates = service.listCandidates(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(candidates))
    }

    private fun handleCastBallot(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val castBallotRequest = json.decodeFromString<CastBallotRequest>(req.body)
        val confirmation = service.castBallot(
            accessToken,
            castBallotRequest.voterName,
            electionName,
            castBallotRequest.rankings,
        )
        return HttpResponse(200, json.encodeToString(confirmation))
    }

    private fun handleGetBallot(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val voterName = extractVoterOrUserName(req.target)
        val ballot = service.getBallot(accessToken, voterName, electionName)
        return HttpResponse(200, json.encodeToString(ballot))
    }

    private fun handleListRankings(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val voterName = extractVoterOrUserName(req.target)
        val rankings = service.listRankings(accessToken, voterName, electionName)
        return HttpResponse(200, json.encodeToString(rankings))
    }

    private fun handleTally(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val tally = service.tally(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(tally))
    }

}
