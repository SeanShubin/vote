package com.seanshubin.vote.documentation

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*
import com.seanshubin.vote.integration.dsl.ScenarioBackend
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Backend that executes operations by making HTTP calls through HttpRecorder.
 * Used for HTTP API documentation generation.
 */
class HttpRecordingBackend(
    private val recorder: HttpRecorder,
    private val documentationRecorder: DocumentationRecorder? = null
) : ScenarioBackend {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

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

    override fun listUsers(token: AccessToken): List<UserNameRole> {
        val response = recorder.get("/users", token)
        return json.decodeFromString<List<UserNameRole>>(response.body())
    }

    override fun getUser(token: AccessToken, userName: String): UserNameEmail {
        val encodedName = URLEncoder.encode(userName, StandardCharsets.UTF_8)
        val response = recorder.get("/user/$encodedName", token)
        return json.decodeFromString<UserNameEmail>(response.body())
    }

    override fun userCount(token: AccessToken): Int {
        val response = recorder.get("/users/count", token)
        val countMap = json.decodeFromString<Map<String, Int>>(response.body())
        return countMap["count"] ?: 0
    }

    override fun listElections(token: AccessToken): List<ElectionSummary> {
        val response = recorder.get("/elections", token)
        return json.decodeFromString<List<ElectionSummary>>(response.body())
    }

    override fun getElection(token: AccessToken, electionName: String): ElectionDetail {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedName", token)
        return json.decodeFromString<ElectionDetail>(response.body())
    }

    override fun electionCount(token: AccessToken): Int {
        val response = recorder.get("/elections/count", token)
        val countMap = json.decodeFromString<Map<String, Int>>(response.body())
        return countMap["count"] ?: 0
    }

    override fun listCandidates(token: AccessToken, electionName: String): List<String> {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedName/candidates", token)
        return json.decodeFromString<List<String>>(response.body())
    }

    override fun listEligibility(token: AccessToken, electionName: String): List<VoterEligibility> {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedName/eligibility", token)
        return json.decodeFromString<List<VoterEligibility>>(response.body())
    }

    override fun isEligible(token: AccessToken, userName: String, electionName: String): Boolean {
        val encodedElection = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val encodedUser = URLEncoder.encode(userName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedElection/eligible/$encodedUser", token)
        return response.body().toBoolean()
    }

    override fun listRankings(token: AccessToken, voterName: String, electionName: String): List<Ranking> {
        val encodedElection = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val encodedVoter = URLEncoder.encode(voterName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedElection/rankings/$encodedVoter", token)
        return json.decodeFromString<List<Ranking>>(response.body())
    }

    override fun listTables(token: AccessToken): List<String> {
        val response = recorder.get("/tables", token)
        return json.decodeFromString<List<String>>(response.body())
    }

    override fun tableCount(token: AccessToken): Int {
        val response = recorder.get("/tables/count", token)
        val countMap = json.decodeFromString<Map<String, Int>>(response.body())
        return countMap["count"] ?: 0
    }

    override fun eventCount(token: AccessToken): Int {
        val response = recorder.get("/events/count", token)
        val countMap = json.decodeFromString<Map<String, Int>>(response.body())
        return countMap["count"] ?: 0
    }

    override fun tableData(token: AccessToken, tableName: String): TableData {
        val encodedName = URLEncoder.encode(tableName, StandardCharsets.UTF_8)
        val response = recorder.get("/table/$encodedName", token)
        return json.decodeFromString<TableData>(response.body())
    }

    override fun permissionsForRole(role: Role): List<Permission> {
        val response = recorder.get("/permissions/${role.name}")
        return json.decodeFromString<List<Permission>>(response.body())
    }

    override fun refresh(refreshToken: RefreshToken): Tokens {
        val body = json.encodeToString(refreshToken)
        val response = recorder.post("/refresh", body)
        return json.decodeFromString<Tokens>(response.body())
    }

    override fun authenticateWithToken(accessToken: AccessToken): Tokens {
        val body = json.encodeToString(accessToken)
        val response = recorder.post("/authenticate-with-token", body)
        return json.decodeFromString<Tokens>(response.body())
    }

    override fun authenticate(nameOrEmail: String, password: String): Tokens {
        val request = AuthenticateRequest(nameOrEmail, password)
        val body = json.encodeToString(request)
        val response = recorder.post("/authenticate", body)
        return json.decodeFromString<Tokens>(response.body())
    }

    override fun updateElection(token: AccessToken, electionName: String, updates: ElectionUpdates) {
        val body = json.encodeToString(updates)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.put("/election/$encodedName", body, token)
    }

    override fun sendLoginLinkByEmail(email: String, baseUri: String) {
        val body = """{"email":"$email","baseUri":"$baseUri"}"""
        recorder.post("/login-link", body)
    }

    override fun synchronize() {
        recorder.post("/sync", "{}")
    }
}
