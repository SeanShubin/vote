package com.seanshubin.vote.documentation

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.BallotSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.domain.UserUpdates
import com.seanshubin.vote.integration.dsl.ScenarioBackend
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Backend that executes operations by making HTTP calls through HttpRecorder.
 * Used for HTTP API documentation generation.
 */
class HttpRecordingBackend(private val recorder: HttpRecorder) : ScenarioBackend {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun registerUser(name: String, email: String, password: String): AccessToken {
        val request = RegisterRequest(name, email, password)
        val body = json.encodeToString(request)
        val response = recorder.post("/register", body)
        return json.decodeFromString<Tokens>(response.body()).accessToken
    }

    override fun changePassword(token: AccessToken, userName: String, newPassword: String) {
        val request = ChangePasswordRequest(newPassword)
        val body = json.encodeToString(request)
        recorder.put("/user/$userName/password", body, token)
    }

    override fun setRole(token: AccessToken, targetUserName: String, newRole: Role) {
        val request = SetRoleRequest(newRole)
        val body = json.encodeToString(request)
        recorder.put("/user/$targetUserName/role", body, token)
    }

    override fun updateUser(token: AccessToken, userName: String, updates: UserUpdates) {
        val body = json.encodeToString(updates)
        recorder.put("/user/$userName", body, token)
    }

    override fun removeUser(token: AccessToken, targetUserName: String) {
        recorder.delete("/user/$targetUserName", token)
    }

    override fun addElection(token: AccessToken, ownerName: String, electionName: String) {
        val request = AddElectionRequest(ownerName, electionName)
        val body = json.encodeToString(request)
        recorder.post("/election", body, token)
    }

    override fun setCandidates(token: AccessToken, electionName: String, candidateNames: List<String>) {
        val request = SetCandidatesRequest(candidateNames)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.put("/election/$encodedName/candidates", body, token)
    }

    override fun setEligibleVoters(token: AccessToken, electionName: String, voterNames: List<String>) {
        val request = SetEligibleVotersRequest(voterNames)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.put("/election/$encodedName/eligibility", body, token)
    }

    override fun launchElection(token: AccessToken, electionName: String, allowEdit: Boolean) {
        val request = LaunchElectionRequest(allowEdit)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.post("/election/$encodedName/launch", body, token)
    }

    override fun finalizeElection(token: AccessToken, electionName: String) {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.post("/election/$encodedName/finalize", "{}", token)
    }

    override fun deleteElection(token: AccessToken, electionName: String) {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.delete("/election/$encodedName", token)
    }

    override fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>) {
        val request = CastBallotRequest(voterName, rankings)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.post("/election/$encodedName/ballot", body, token)
    }

    override fun getBallot(token: AccessToken, voterName: String, electionName: String): BallotSummary? {
        val encodedElection = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val encodedVoter = URLEncoder.encode(voterName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedElection/ballot/$encodedVoter", token)
        return if (response.statusCode() == 200) {
            json.decodeFromString<BallotSummary>(response.body())
        } else {
            null
        }
    }

    override fun tally(token: AccessToken, electionName: String): Tally {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedName/tally", token)
        return json.decodeFromString<Tally>(response.body())
    }

    override fun synchronize() {
        // HTTP backend doesn't need synchronization - operations are immediate
    }
}
