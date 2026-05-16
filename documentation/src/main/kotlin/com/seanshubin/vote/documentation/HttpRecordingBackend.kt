package com.seanshubin.vote.documentation

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*
import com.seanshubin.vote.integration.dsl.ScenarioBackend
import kotlinx.datetime.Instant
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

    override fun seedEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        recorder.seedEvent(authority, whenHappened, event)
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

    override fun addElection(token: AccessToken, ownerName: String, electionName: String, description: String) {
        val request = AddElectionRequest(ownerName, electionName, description)
        val body = json.encodeToString(request)
        recorder.post("/election", body, token)
    }

    override fun addCandidates(token: AccessToken, electionName: String, candidateNames: List<String>) {
        val request = AddCandidatesRequest(candidateNames)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.post("/election/$encodedName/candidate-add", body, token)
    }

    override fun removeCandidate(token: AccessToken, electionName: String, candidateName: String) {
        val encodedElection = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val encodedCandidate = URLEncoder.encode(candidateName, StandardCharsets.UTF_8)
        recorder.delete("/election/$encodedElection/candidate/$encodedCandidate", token)
    }

    override fun setTiers(token: AccessToken, electionName: String, tierNames: List<String>) {
        val request = SetTiersRequest(tierNames)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.put("/election/$encodedName/tiers", body, token)
    }

    override fun deleteElection(token: AccessToken, electionName: String) {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.delete("/election/$encodedName", token)
    }

    override fun transferElectionOwnership(token: AccessToken, electionName: String, newOwnerName: String) {
        val request = TransferElectionOwnershipRequest(newOwnerName)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.put("/election/$encodedName/owner", body, token)
    }

    override fun addElectionManager(token: AccessToken, electionName: String, userName: String) {
        val request = AddElectionManagerRequest(userName)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        recorder.post("/election/$encodedName/manager-add", body, token)
    }

    override fun removeElectionManager(token: AccessToken, electionName: String, userName: String) {
        val encodedElection = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val encodedUser = URLEncoder.encode(userName, StandardCharsets.UTF_8)
        recorder.delete("/election/$encodedElection/manager/$encodedUser", token)
    }

    override fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>): String {
        val request = CastBallotRequest(voterName, rankings)
        val body = json.encodeToString(request)
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val response = recorder.post("/election/$encodedName/ballot", body, token)
        return json.decodeFromString<String>(response.body())
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

    override fun tally(token: AccessToken, electionName: String, side: RankingSide): ElectionTally {
        val encodedName = URLEncoder.encode(electionName, StandardCharsets.UTF_8)
        val response = recorder.get("/election/$encodedName/tally?side=$side", token)
        return json.decodeFromString<ElectionTally>(response.body())
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
        throw UnsupportedOperationException("refresh not supported in HttpRecordingBackend")
    }

    override fun authenticateWithToken(accessToken: AccessToken): Tokens {
        throw UnsupportedOperationException("authenticateWithToken not supported in HttpRecordingBackend")
    }

    override fun pauseEventLog(token: AccessToken) {
        recorder.post("/admin/event-log/pause", "", token)
    }

    override fun resumeEventLog(token: AccessToken) {
        recorder.post("/admin/event-log/resume", "", token)
    }

    override fun isEventLogPaused(): Boolean {
        val response = recorder.get("/admin/event-log/status")
        val body = json.decodeFromString<Map<String, Boolean>>(response.body())
        return body["paused"] ?: false
    }

    override fun listFeatureFlags(): Map<FeatureFlag, Boolean> {
        val response = recorder.get("/admin/feature-flags")
        val byName = json.decodeFromString<Map<String, Boolean>>(response.body())
        return FeatureFlag.entries.associateWith { flag ->
            byName[flag.name] ?: flag.defaultEnabled
        }
    }

    override fun setFeatureEnabled(token: AccessToken, flag: FeatureFlag, enabled: Boolean) {
        val body = json.encodeToString(mapOf("enabled" to enabled))
        recorder.put("/admin/feature-flags/${URLEncoder.encode(flag.name, StandardCharsets.UTF_8)}", body, token)
    }

    override fun synchronize() {
        recorder.post("/sync", "{}")
    }
}
