package com.seanshubin.vote.backend.http

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.ElectionUpdates
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.UserUpdates
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.slf4j.LoggerFactory

class SimpleHttpHandler(
    private val service: Service,
    private val json: Json
) : AbstractHandler() {
    private val log = LoggerFactory.getLogger(SimpleHttpHandler::class.java)

    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        baseRequest.isHandled = true
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"

        log.debug("${request.method} $target")

        try {
            when {
                target == "/health" && request.method == "GET" -> handleHealth(response)
                target == "/register" && request.method == "POST" -> handleRegister(request, response)
                target == "/authenticate" && request.method == "POST" -> handleAuthenticate(request, response)
                target == "/refresh" && request.method == "POST" -> handleRefresh(request, response)
                target == "/users" && request.method == "GET" -> handleListUsers(request, response)
                target == "/users/count" && request.method == "GET" -> handleUserCount(request, response)
                target.matches(Regex("/user/[^/]+")) && request.method == "GET" -> handleGetUser(request, response, target)
                target.matches(Regex("/user/[^/]+")) && request.method == "PUT" -> handleUpdateUser(request, response, target)
                target.matches(Regex("/user/[^/]+")) && request.method == "DELETE" -> handleRemoveUser(request, response, target)
                target.matches(Regex("/user/[^/]+/role")) && request.method == "PUT" -> handleSetRole(request, response, target)
                target.matches(Regex("/user/[^/]+/password")) && request.method == "PUT" -> handleChangePassword(request, response, target)
                target.matches(Regex("/permissions/[^/]+")) && request.method == "GET" -> handlePermissionsForRole(response, target)
                target == "/election" && request.method == "POST" -> handleAddElection(request, response)
                target == "/elections" && request.method == "GET" -> handleListElections(request, response)
                target == "/elections/count" && request.method == "GET" -> handleElectionCount(request, response)
                target.matches(Regex("/election/[^/]+")) && request.method == "GET" -> handleGetElection(request, response, target)
                target.matches(Regex("/election/[^/]+")) && request.method == "PUT" -> handleUpdateElection(request, response, target)
                target.matches(Regex("/election/[^/]+")) && request.method == "DELETE" -> handleDeleteElection(request, response, target)
                target.matches(Regex("/election/[^/]+/launch")) && request.method == "POST" -> handleLaunchElection(request, response, target)
                target.matches(Regex("/election/[^/]+/finalize")) && request.method == "POST" -> handleFinalizeElection(request, response, target)
                target.matches(Regex("/election/[^/]+/candidates")) && request.method == "PUT" -> handleSetCandidates(request, response, target)
                target.matches(Regex("/election/[^/]+/candidates")) && request.method == "GET" -> handleListCandidates(request, response, target)
                target.matches(Regex("/election/[^/]+/ballot")) && request.method == "POST" -> handleCastBallot(request, response, target)
                target.matches(Regex("/election/[^/]+/ballot/[^/]+")) && request.method == "GET" -> handleGetBallot(request, response, target)
                target.matches(Regex("/election/[^/]+/rankings/[^/]+")) && request.method == "GET" -> handleListRankings(request, response, target)
                target.matches(Regex("/election/[^/]+/tally")) && request.method == "GET" -> handleTally(request, response, target)
                target.matches(Regex("/election/[^/]+/eligibility")) && request.method == "PUT" -> handleSetEligibleVoters(request, response, target)
                target.matches(Regex("/election/[^/]+/eligibility")) && request.method == "GET" -> handleListEligibility(request, response, target)
                target.matches(Regex("/election/[^/]+/eligibility/[^/]+")) && request.method == "GET" -> handleIsEligible(request, response, target)
                else -> {
                    response.status = HttpServletResponse.SC_NOT_FOUND
                    response.writer.write(json.encodeToString(ErrorResponse("Not found: ${request.method} $target")))
                }
            }
        } catch (e: IllegalArgumentException) {
            log.warn("Bad request: ${request.method} $target - ${e.message}", e)
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.writer.write(json.encodeToString(ErrorResponse(e.message ?: "Bad request")))
        } catch (e: ServiceException) {
            log.info("Service exception: ${request.method} $target - ${e.category}: ${e.message}")
            response.status = when (e.category) {
                ServiceException.Category.UNAUTHORIZED -> HttpServletResponse.SC_UNAUTHORIZED
                ServiceException.Category.NOT_FOUND -> HttpServletResponse.SC_NOT_FOUND
                ServiceException.Category.CONFLICT -> HttpServletResponse.SC_CONFLICT
                ServiceException.Category.UNSUPPORTED -> HttpServletResponse.SC_NOT_IMPLEMENTED
                ServiceException.Category.MALFORMED_JSON -> HttpServletResponse.SC_BAD_REQUEST
            }
            response.writer.write(json.encodeToString(ErrorResponse(e.message ?: "Service error")))
        } catch (e: Exception) {
            log.error("Unhandled exception: ${request.method} $target", e)
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.writer.write(json.encodeToString(ErrorResponse(e.message ?: "Unknown error")))
        }
    }

    private fun extractAccessToken(request: HttpServletRequest): AccessToken {
        val authHeader = request.getHeader("Authorization")
            ?: throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Missing Authorization header")

        if (!authHeader.startsWith("Bearer ")) {
            throw ServiceException(ServiceException.Category.UNAUTHORIZED, "Invalid Authorization header format")
        }

        val tokenJson = authHeader.substring(7)
        return json.decodeFromString<AccessToken>(tokenJson)
    }

    private fun extractUserName(target: String): String {
        val parts = target.split("/")
        return parts[2]
    }

    private fun extractElectionName(target: String): String {
        val parts = target.split("/")
        return parts[2]
    }

    private fun extractVoterOrUserName(target: String): String {
        val parts = target.split("/")
        return parts[4]
    }

    private fun handleHealth(response: HttpServletResponse) {
        val result = service.health()
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to result)))
    }

    private fun handleRegister(request: HttpServletRequest, response: HttpServletResponse) {
        val body = request.reader.readText()
        val registerRequest = json.decodeFromString<RegisterRequest>(body)
        val tokens = service.register(
            userName = registerRequest.userName,
            email = registerRequest.email,
            password = registerRequest.password
        )
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(tokens))
    }

    private fun handleAuthenticate(request: HttpServletRequest, response: HttpServletResponse) {
        val body = request.reader.readText()
        val authRequest = json.decodeFromString<AuthenticateRequest>(body)
        val tokens = service.authenticate(
            nameOrEmail = authRequest.nameOrEmail,
            password = authRequest.password
        )
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(tokens))
    }

    private fun handleRefresh(request: HttpServletRequest, response: HttpServletResponse) {
        val body = request.reader.readText()
        val refreshToken = json.decodeFromString<RefreshToken>(body)
        val tokens = service.refresh(refreshToken)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(tokens))
    }

    private fun handleListUsers(request: HttpServletRequest, response: HttpServletResponse) {
        val accessToken = extractAccessToken(request)
        val users = service.listUsers(accessToken)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(users))
    }

    private fun handleUserCount(request: HttpServletRequest, response: HttpServletResponse) {
        val accessToken = extractAccessToken(request)
        val count = service.userCount(accessToken)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("count" to count)))
    }

    private fun handleGetUser(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val userName = extractUserName(target)
        val user = service.getUser(accessToken, userName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(user))
    }

    private fun handleUpdateUser(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val userName = extractUserName(target)
        val body = request.reader.readText()
        val userUpdates = json.decodeFromString<UserUpdates>(body)
        service.updateUser(accessToken, userName, userUpdates)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "updated")))
    }

    private fun handleRemoveUser(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val userName = extractUserName(target)
        service.removeUser(accessToken, userName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "removed")))
    }

    private fun handleSetRole(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val userName = extractUserName(target)
        val body = request.reader.readText()
        val setRoleRequest = json.decodeFromString<SetRoleRequest>(body)
        service.setRole(accessToken, userName, setRoleRequest.role)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "role updated")))
    }

    private fun handleChangePassword(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val userName = extractUserName(target)
        val body = request.reader.readText()
        val changePasswordRequest = json.decodeFromString<ChangePasswordRequest>(body)
        service.changePassword(accessToken, userName, changePasswordRequest.password)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "password changed")))
    }

    private fun handlePermissionsForRole(response: HttpServletResponse, target: String) {
        val parts = target.split("/")
        val roleString = parts[2]
        val role = Role.valueOf(roleString)
        val permissions = service.permissionsForRole(role)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(permissions))
    }

    private fun handleAddElection(request: HttpServletRequest, response: HttpServletResponse) {
        val accessToken = extractAccessToken(request)
        val body = request.reader.readText()
        val addElectionRequest = json.decodeFromString<AddElectionRequest>(body)
        service.addElection(accessToken, addElectionRequest.userName, addElectionRequest.electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "election created")))
    }

    private fun handleListElections(request: HttpServletRequest, response: HttpServletResponse) {
        val accessToken = extractAccessToken(request)
        val elections = service.listElections(accessToken)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(elections))
    }

    private fun handleElectionCount(request: HttpServletRequest, response: HttpServletResponse) {
        val accessToken = extractAccessToken(request)
        val count = service.electionCount(accessToken)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("count" to count)))
    }

    private fun handleGetElection(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val election = service.getElection(accessToken, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(election))
    }

    private fun handleUpdateElection(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val body = request.reader.readText()
        val electionUpdates = json.decodeFromString<ElectionUpdates>(body)
        service.updateElection(accessToken, electionName, electionUpdates)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "election updated")))
    }

    private fun handleDeleteElection(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        service.deleteElection(accessToken, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "election deleted")))
    }

    private fun handleLaunchElection(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val body = request.reader.readText()
        val launchRequest = json.decodeFromString<LaunchElectionRequest>(body)
        service.launchElection(accessToken, electionName, launchRequest.allowEdit)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "election launched")))
    }

    private fun handleFinalizeElection(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        service.finalizeElection(accessToken, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "election finalized")))
    }

    private fun handleSetCandidates(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val body = request.reader.readText()
        val setCandidatesRequest = json.decodeFromString<SetCandidatesRequest>(body)
        service.setCandidates(accessToken, electionName, setCandidatesRequest.candidateNames)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "candidates updated")))
    }

    private fun handleListCandidates(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val candidates = service.listCandidates(accessToken, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(candidates))
    }

    private fun handleCastBallot(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val body = request.reader.readText()
        val castBallotRequest = json.decodeFromString<CastBallotRequest>(body)
        service.castBallot(
            accessToken,
            castBallotRequest.voterName,
            electionName,
            castBallotRequest.rankings
        )
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "ballot cast")))
    }

    private fun handleGetBallot(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val voterName = extractVoterOrUserName(target)
        val ballot = service.getBallot(accessToken, voterName, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(ballot))
    }

    private fun handleListRankings(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val voterName = extractVoterOrUserName(target)
        val rankings = service.listRankings(accessToken, voterName, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(rankings))
    }

    private fun handleTally(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val tally = service.tally(accessToken, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(tally))
    }

    private fun handleSetEligibleVoters(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val body = request.reader.readText()
        val setEligibleVotersRequest = json.decodeFromString<SetEligibleVotersRequest>(body)
        service.setEligibleVoters(accessToken, electionName, setEligibleVotersRequest.voterNames)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("status" to "eligible voters updated")))
    }

    private fun handleListEligibility(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val eligibility = service.listEligibility(accessToken, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(eligibility))
    }

    private fun handleIsEligible(request: HttpServletRequest, response: HttpServletResponse, target: String) {
        val accessToken = extractAccessToken(request)
        val electionName = extractElectionName(target)
        val userName = extractVoterOrUserName(target)
        val isEligible = service.isEligible(accessToken, userName, electionName)
        response.status = HttpServletResponse.SC_OK
        response.writer.write(json.encodeToString(mapOf("eligible" to isEligible)))
    }
}
