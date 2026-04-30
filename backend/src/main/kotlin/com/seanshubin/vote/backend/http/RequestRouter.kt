package com.seanshubin.vote.backend.http

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.AddElectionRequest
import com.seanshubin.vote.contract.AuthenticateRequest
import com.seanshubin.vote.contract.CastBallotRequest
import com.seanshubin.vote.contract.ChangePasswordRequest
import com.seanshubin.vote.contract.ClientErrorRequest
import com.seanshubin.vote.contract.ErrorResponse
import com.seanshubin.vote.contract.LaunchElectionRequest
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.contract.RegisterRequest
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.contract.SetCandidatesRequest
import com.seanshubin.vote.contract.SetEligibleVotersRequest
import com.seanshubin.vote.contract.SetRoleRequest
import com.seanshubin.vote.domain.ElectionUpdates
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
 */
class RequestRouter(
    private val service: Service,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(RequestRouter::class.java)

    fun route(req: HttpRequest): HttpResponse {
        log.debug("${req.method} ${req.target}")

        if (req.method == "OPTIONS") {
            return HttpResponse(200, "")
        }

        return try {
            dispatch(req)
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
            target.matches(Regex("/table/[^/]+")) && method == "GET" -> handleTableData(req)
            target == "/election" && method == "POST" -> handleAddElection(req)
            target == "/elections" && method == "GET" -> handleListElections(req)
            target == "/elections/count" && method == "GET" -> handleElectionCount(req)
            target.matches(Regex("/election/[^/]+")) && method == "GET" -> handleGetElection(req)
            target.matches(Regex("/election/[^/]+")) && method == "PUT" -> handleUpdateElection(req)
            target.matches(Regex("/election/[^/]+")) && method == "DELETE" -> handleDeleteElection(req)
            target.matches(Regex("/election/[^/]+/launch")) && method == "POST" -> handleLaunchElection(req)
            target.matches(Regex("/election/[^/]+/finalize")) && method == "POST" -> handleFinalizeElection(req)
            target.matches(Regex("/election/[^/]+/candidates")) && method == "PUT" -> handleSetCandidates(req)
            target.matches(Regex("/election/[^/]+/candidates")) && method == "GET" -> handleListCandidates(req)
            target.matches(Regex("/election/[^/]+/ballot")) && method == "POST" -> handleCastBallot(req)
            target.matches(Regex("/election/[^/]+/ballot/[^/]+")) && method == "GET" -> handleGetBallot(req)
            target.matches(Regex("/election/[^/]+/rankings/[^/]+")) && method == "GET" -> handleListRankings(req)
            target.matches(Regex("/election/[^/]+/tally")) && method == "GET" -> handleTally(req)
            target.matches(Regex("/election/[^/]+/eligibility")) && method == "PUT" -> handleSetEligibleVoters(req)
            target.matches(Regex("/election/[^/]+/eligibility")) && method == "GET" -> handleListEligibility(req)
            target.matches(Regex("/election/[^/]+/eligibility/[^/]+")) && method == "GET" -> handleIsEligible(req)
            else -> errorResponse(404, "Not found: $method $target")
        }
    }

    private fun errorResponse(status: Int, message: String): HttpResponse =
        HttpResponse(status, json.encodeToString(ErrorResponse(message)))

    private fun extractAccessToken(req: HttpRequest): AccessToken {
        val authHeader = req.header("Authorization")
            ?: throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Missing Authorization header")
        if (!authHeader.startsWith("Bearer ")) {
            throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Invalid Authorization header format")
        }
        val tokenJson = authHeader.substring(7)
        return json.decodeFromString<AccessToken>(tokenJson)
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
        return HttpResponse(200, json.encodeToString(tokens))
    }

    private fun handleAuthenticate(req: HttpRequest): HttpResponse {
        val authRequest = json.decodeFromString<AuthenticateRequest>(req.body)
        val tokens = service.authenticate(
            nameOrEmail = authRequest.nameOrEmail,
            password = authRequest.password,
        )
        return HttpResponse(200, json.encodeToString(tokens))
    }

    private fun handleRefresh(req: HttpRequest): HttpResponse {
        val refreshToken = json.decodeFromString<RefreshToken>(req.body)
        val tokens = service.refresh(refreshToken)
        return HttpResponse(200, json.encodeToString(tokens))
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

    private fun handleUpdateElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val electionUpdates = json.decodeFromString<ElectionUpdates>(req.body)
        service.updateElection(accessToken, electionName, electionUpdates)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "election updated")))
    }

    private fun handleDeleteElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        service.deleteElection(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "election deleted")))
    }

    private fun handleLaunchElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val launchRequest = json.decodeFromString<LaunchElectionRequest>(req.body)
        service.launchElection(accessToken, electionName, launchRequest.allowEdit)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "election launched")))
    }

    private fun handleFinalizeElection(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        service.finalizeElection(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "election finalized")))
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
        // Return the confirmation as a JSON string literal so ApiClient.castBallot's
        // declared `String` return type deserializes cleanly. (Returning an object
        // here was the source of the "Unexpected JSON token at offset 0" error.)
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

    private fun handleSetEligibleVoters(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val setEligibleVotersRequest = json.decodeFromString<SetEligibleVotersRequest>(req.body)
        service.setEligibleVoters(accessToken, electionName, setEligibleVotersRequest.voterNames)
        return HttpResponse(200, json.encodeToString(mapOf("status" to "eligible voters updated")))
    }

    private fun handleListEligibility(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val eligibility = service.listEligibility(accessToken, electionName)
        return HttpResponse(200, json.encodeToString(eligibility))
    }

    private fun handleIsEligible(req: HttpRequest): HttpResponse {
        val accessToken = extractAccessToken(req)
        val electionName = extractElectionName(req.target)
        val userName = extractVoterOrUserName(req.target)
        val isEligible = service.isEligible(accessToken, userName, electionName)
        return HttpResponse(200, json.encodeToString(mapOf("eligible" to isEligible)))
    }
}
