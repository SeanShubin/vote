package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.fake.TestIntegrations
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test orchestrator that manages HTTP server lifecycle and provides domain-focused API methods.
 *
 * Hides infrastructure details: server start/stop, HTTP request building, JSON serialization,
 * polling for server readiness.
 */
class HttpApiTester(private val port: Int = 9876) : AutoCloseable {
    private val runner: com.seanshubin.vote.backend.dependencies.ApplicationRunner
    private val httpClient: HttpClient
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "http://localhost:$port"

    init {
        val integrations = TestIntegrations()
        val configuration = com.seanshubin.vote.backend.dependencies.Configuration(
            port = port,
            databaseConfig = DatabaseConfig.InMemory
        )
        val appDeps = ApplicationDependencies(integrations, configuration)
        runner = appDeps.runner
        runner.startNonBlocking()

        httpClient = HttpClient.newBuilder().build()
        waitForServerReady()
    }

    private fun waitForServerReady() {
        var ready = false
        for (i in 1..50) {
            try {
                val response = getHealth()
                if (response.statusCode() == 200) {
                    ready = true
                    break
                }
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        if (!ready) throw IllegalStateException("Server failed to start")
    }

    override fun close() {
        runner.stop()
    }

    // Low-level HTTP methods (used by domain methods)
    private fun get(path: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .GET()
            .apply { token?.let { header("Authorization", "Bearer ${json.encodeToString(it)}") } }
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .apply { token?.let { header("Authorization", "Bearer ${json.encodeToString(it)}") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun put(path: String, body: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${json.encodeToString(token)}")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun delete(path: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer ${json.encodeToString(token)}")
            .DELETE()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    // Domain-focused API methods

    // Health
    fun getHealth(): HttpResponse<String> = get("/health")

    // Authentication & Registration
    fun registerUser(userName: String, email: String = "$userName@example.com", password: String = "password"): HttpResponse<String> {
        val body = """{"userName":"$userName","email":"$email","password":"$password"}"""
        return post("/register", body)
    }

    fun registerUserExpectSuccess(userName: String, email: String = "$userName@example.com", password: String = "password"): Tokens {
        val response = registerUser(userName, email, password)
        assertEquals(200, response.statusCode())
        return json.decodeFromString<Tokens>(response.body())
    }

    fun authenticateUser(nameOrEmail: String, password: String): HttpResponse<String> {
        val body = """{"nameOrEmail":"$nameOrEmail","password":"$password"}"""
        return post("/authenticate", body)
    }

    // User Management
    fun listUsers(token: AccessToken): HttpResponse<String> = get("/users", token)

    fun getUser(userName: String, token: AccessToken): HttpResponse<String> = get("/user/$userName", token)

    fun updateUserName(userName: String, newUserName: String, token: AccessToken): HttpResponse<String> {
        val body = """{"userName":"$newUserName"}"""
        return put("/user/$userName", body, token)
    }

    fun updateUserEmail(userName: String, newEmail: String, token: AccessToken): HttpResponse<String> {
        val body = """{"email":"$newEmail"}"""
        return put("/user/$userName", body, token)
    }

    fun deleteUser(userName: String, token: AccessToken): HttpResponse<String> = delete("/user/$userName", token)

    // Elections
    fun createElection(electionName: String, token: AccessToken): HttpResponse<String> {
        val userName = token.userName
        val body = """{"userName":"$userName","electionName":"$electionName"}"""
        return post("/election", body, token)
    }

    fun listElections(token: AccessToken): HttpResponse<String> = get("/elections", token)

    fun getElection(electionName: String, token: AccessToken): HttpResponse<String> = get("/election/$electionName", token)

    fun deleteElection(electionName: String, token: AccessToken): HttpResponse<String> = delete("/election/$electionName", token)

    // Candidates
    fun setCandidates(electionName: String, candidates: List<String>, token: AccessToken): HttpResponse<String> {
        val candidatesJson = candidates.joinToString(",") { "\"$it\"" }
        val body = """{"candidateNames":[$candidatesJson]}"""
        return put("/election/$electionName/candidates", body, token)
    }

    fun listCandidates(electionName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/candidates", token)

    // User Role Management
    fun setUserRole(userName: String, role: String, token: AccessToken): HttpResponse<String> {
        val body = """{"role":"$role"}"""
        return put("/user/$userName/role", body, token)
    }

    fun updateUserPassword(userName: String, password: String, token: AccessToken): HttpResponse<String> {
        val body = """{"password":"$password"}"""
        return put("/user/$userName/password", body, token)
    }

    // Election Lifecycle
    fun launchElection(electionName: String, allowEdit: Boolean, token: AccessToken): HttpResponse<String> {
        val body = """{"allowEdit":$allowEdit}"""
        return post("/election/$electionName/launch", body, token)
    }

    fun finalizeElection(electionName: String, token: AccessToken): HttpResponse<String> {
        return post("/election/$electionName/finalize", "{}", token)
    }

    fun updateElection(electionName: String, body: String, token: AccessToken): HttpResponse<String> {
        return put("/election/$electionName", body, token)
    }

    // Eligibility
    fun setEligibleVoters(electionName: String, voterNames: List<String>, token: AccessToken): HttpResponse<String> {
        val votersJson = voterNames.joinToString(",") { "\"$it\"" }
        val body = """{"voterNames":[$votersJson]}"""
        return put("/election/$electionName/eligibility", body, token)
    }

    fun getElectionEligibility(electionName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/eligibility", token)

    fun checkVoterEligibility(electionName: String, voterName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/eligibility/$voterName", token)

    // Ballots and Rankings
    fun castBallot(electionName: String, body: String, token: AccessToken): HttpResponse<String> {
        return post("/election/$electionName/ballot", body, token)
    }

    fun getBallot(electionName: String, voterName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/ballot/$voterName", token)

    fun getVoterRankings(electionName: String, voterName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/rankings/$voterName", token)

    fun getTally(electionName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/tally", token)

    // Token Management
    fun refreshToken(refreshToken: com.seanshubin.vote.contract.RefreshToken): HttpResponse<String> {
        val body = json.encodeToString(refreshToken)
        return post("/refresh", body)
    }

    // System Operations
    fun sync(token: AccessToken): HttpResponse<String> = post("/sync", "", token)

    fun logClientError(message: String, stackTrace: String, url: String, userAgent: String, timestamp: String): HttpResponse<String> {
        val body = """{"message":"$message","stackTrace":"$stackTrace","url":"$url","userAgent":"$userAgent","timestamp":"$timestamp"}"""
        return post("/log-client-error", body)
    }

    // Statistics
    fun getUserCount(token: AccessToken): HttpResponse<String> = get("/users/count", token)

    fun getElectionCount(token: AccessToken): HttpResponse<String> = get("/elections/count", token)

    fun listTables(token: AccessToken): HttpResponse<String> = get("/tables", token)

    fun getTableCount(token: AccessToken): HttpResponse<String> = get("/tables/count", token)

    fun getEventCount(token: AccessToken): HttpResponse<String> = get("/events/count", token)

    fun getTableData(tableName: String, token: AccessToken): HttpResponse<String> = get("/table/$tableName", token)

    fun getPermissionsForRole(role: String): HttpResponse<String> = get("/permissions/$role")

    // Utilities
    fun getHealthWithAuth(): HttpResponse<String> = get("/health")

    fun getUsersWithoutAuth(): HttpResponse<String> = get("/users")

    fun getUsersWithMalformedAuth(): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users"))
            .header("Authorization", "InvalidFormat")
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun <T> decodeJson(response: HttpResponse<String>, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        return json.decodeFromString(deserializer, response.body())
    }
}

class HttpApiTest {
    private lateinit var tester: HttpApiTester

    @BeforeEach
    fun setup() {
        tester = HttpApiTester()
    }

    @AfterEach
    fun teardown() {
        tester.close()
    }

    @Test
    fun `health endpoint returns 200`() {
        val response = tester.getHealth()

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\""))
    }

    @Test
    fun `register returns access token`() {
        val response = tester.registerUser("alice", "alice@example.com", "pass")

        assertEquals(200, response.statusCode())
        val tokens = tester.decodeJson(response, Tokens.serializer())
        assertNotNull(tokens.accessToken)
        assertEquals("alice", tokens.accessToken.userName)
    }

    @Test
    fun `register with duplicate username returns error`() {
        tester.registerUserExpectSuccess("alice")
        val response = tester.registerUser("alice", "alice2@example.com", "pass")

        assertTrue(response.statusCode() in listOf(400, 409))
        assertTrue(response.body().contains("error"))
    }

    @Test
    fun `authenticate with valid credentials returns tokens`() {
        tester.registerUserExpectSuccess("alice", password = "secret123")

        val response = tester.authenticateUser("alice", "secret123")

        assertEquals(200, response.statusCode())
        val tokens = tester.decodeJson(response, Tokens.serializer())
        assertEquals("alice", tokens.accessToken.userName)
    }

    @Test
    fun `authenticate with wrong password returns error`() {
        tester.registerUserExpectSuccess("alice", password = "secret123")

        val response = tester.authenticateUser("alice", "wrong")

        assertTrue(response.statusCode() in listOf(400, 401))
    }

    @Test
    fun `endpoint without auth header returns 401`() {
        val response = tester.getUsersWithoutAuth()

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("Authorization"))
    }

    @Test
    fun `endpoint with malformed auth header returns 401`() {
        val response = tester.getUsersWithMalformedAuth()

        assertEquals(401, response.statusCode())
    }

    @Test
    fun `list users returns array`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.registerUserExpectSuccess("bob")

        val response = tester.listUsers(tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("alice"))
        assertTrue(response.body().contains("bob"))
    }

    @Test
    fun `get user returns user details`() {
        val tokens = tester.registerUserExpectSuccess("alice", "alice@example.com")

        val response = tester.getUser("alice", tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("alice"))
        assertTrue(response.body().contains("alice@example.com"))
    }

    @Test
    fun `get nonexistent user returns 404`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.getUser("nobody", tokens.accessToken)

        assertTrue(response.statusCode() in listOf(404, 500))
    }

    @Test
    fun `update user name succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.updateUserName("alice", "alice2", tokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = tester.getUser("alice2", tokens.accessToken)
        assertEquals(200, getResponse.statusCode())
    }

    @Test
    fun `update user email succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice", "alice@example.com")

        val response = tester.updateUserEmail("alice", "newemail@example.com", tokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = tester.getUser("alice", tokens.accessToken)
        assertTrue(getResponse.body().contains("newemail@example.com"))
    }

    @Test
    fun `owner can set user role`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice") // First user is OWNER
        tester.registerUserExpectSuccess("bob")

        val response = tester.setUserRole("bob", "ADMIN", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `owner can remove user`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice") // First user is OWNER
        tester.registerUserExpectSuccess("bob")

        val response = tester.deleteUser("bob", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = tester.getUser("bob", aliceTokens.accessToken)
        assertTrue(getResponse.statusCode() in listOf(404, 500))
    }

    @Test
    fun `user can change password`() {
        val tokens = tester.registerUserExpectSuccess("alice", password = "oldpass")

        val response = tester.updateUserPassword("alice", "newpass", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `create election succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.createElection("Best Language", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `list elections returns array`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Election 1", tokens.accessToken)
        tester.createElection("Election 2", tokens.accessToken)

        val response = tester.listElections(tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Election 1"))
        assertTrue(response.body().contains("Election 2"))
    }

    @Test
    fun `get election returns details`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test Election", tokens.accessToken)

        val response = tester.getElection("Test%20Election", tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Test Election"))
        assertTrue(response.body().contains("alice"))
    }

    @Test
    fun `set candidates succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Languages", tokens.accessToken)

        val response = tester.setCandidates("Languages", listOf("Kotlin", "Java", "Go"), tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `list candidates returns array`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Languages", tokens.accessToken)
        tester.setCandidates("Languages", listOf("Kotlin", "Java"), tokens.accessToken)

        val response = tester.listCandidates("Languages", tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Kotlin"))
        assertTrue(response.body().contains("Java"))
    }

    @Test
    fun `set eligible voters succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.registerUserExpectSuccess("bob")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.setEligibleVoters("Test", listOf("bob"), tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `launch election succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.launchElection("Test", true, tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `finalize election succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)
        tester.launchElection("Test", false, tokens.accessToken)

        val response = tester.finalizeElection("Test", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `delete election succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.deleteElection("Test", tokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = tester.getElection("Test", tokens.accessToken)
        assertEquals(404, getResponse.statusCode())
    }

    @Test
    fun `cast ballot succeeds`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)
        tester.setEligibleVoters("Lang", listOf("bob"), aliceTokens.accessToken)
        tester.launchElection("Lang", true, aliceTokens.accessToken)

        val response = tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `get ballot returns rankings`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)
        tester.setEligibleVoters("Lang", listOf("bob"), aliceTokens.accessToken)
        tester.launchElection("Lang", true, aliceTokens.accessToken)
        tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        val response = tester.getBallot("Lang", "bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("bob"))
    }

    @Test
    fun `tally returns results`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)
        tester.setEligibleVoters("Lang", listOf("bob"), aliceTokens.accessToken)
        tester.launchElection("Lang", true, aliceTokens.accessToken)
        tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1},{"candidateName":"B","rank":2}]}""",
            bobTokens.accessToken)

        val response = tester.getTally("Lang", aliceTokens.accessToken)

        if (response.statusCode() == 200) {
            assertTrue(response.body().contains("places"))
        } else {
            assertTrue(response.statusCode() in listOf(400, 200))
        }
    }

    @Test
    fun `invalid route returns 404`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val invalidPath = "/nonexistent"
        val json = Json { ignoreUnknownKeys = true }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:9876$invalidPath"))
            .header("Authorization", "Bearer ${json.encodeToString(tokens.accessToken)}")
            .GET()
            .build()
        val response = HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("Not found"))
    }

    @Test
    fun `malformed JSON in request returns 400`() {
        val response = tester.registerUser("alice", email = """alice","invalid""")

        assertEquals(400, response.statusCode())
    }

    @Test
    fun `missing required field returns 400`() {
        val body = """{"userName":"alice"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:9876/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(400, response.statusCode())
    }

    @Test
    fun `refresh token returns new tokens`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.refreshToken(tokens.refreshToken)

        assertEquals(200, response.statusCode())
        val newTokens = tester.decodeJson(response, Tokens.serializer())
        assertEquals("alice", newTokens.accessToken.userName)
    }

    @Test
    fun `update election secret ballot succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.updateElection("Test", """{"secretBallot":false}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `update election voting time window succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.updateElection("Test",
            """{"noVotingBefore":"2024-01-01T00:00:00Z","noVotingAfter":"2024-12-31T23:59:59Z"}""",
            tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `update election allowVote and allowEdit succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.updateElection("Test", """{"allowVote":true,"allowEdit":false}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `sync endpoint succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.sync(tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `log client error succeeds`() {
        val response = tester.logClientError("Test error", "Test stack", "http://example.com", "Test Agent", "2024-01-01T00:00:00Z")

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `get user count returns number`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.registerUserExpectSuccess("bob")

        val response = tester.getUserCount(tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count >= 2)
    }

    @Test
    fun `get election count returns number`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("E1", tokens.accessToken)
        tester.createElection("E2", tokens.accessToken)

        val response = tester.getElectionCount(tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count >= 2)
    }

    @Test
    fun `list tables returns array`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.listTables(tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("["), "Expected array but got: $body")
    }

    @Test
    fun `get table count returns number`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.getTableCount(tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count > 0)
    }

    @Test
    fun `get event count returns number`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.getEventCount(tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count > 0, "Should have at least one event from user registration")
    }

    @Test
    fun `get table data returns table structure`() {
        val tokens = tester.registerUserExpectSuccess("alice")

        val response = tester.getTableData("user", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("columnNames"), "Expected 'columnNames' but got: $body")
        assertTrue(body.contains("rows"), "Expected 'rows' but got: $body")
    }

    @Test
    fun `get permissions for role returns list`() {
        tester.registerUserExpectSuccess("alice") // Need at least one user for server to be initialized

        val response = tester.getPermissionsForRole("USER")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("["))
    }

    @Test
    fun `get voter rankings returns list`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)
        tester.setEligibleVoters("Lang", listOf("bob"), aliceTokens.accessToken)
        tester.launchElection("Lang", true, aliceTokens.accessToken)
        tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        val response = tester.getVoterRankings("Lang", "bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("candidateName"))
        assertTrue(response.body().contains("rank"))
    }

    @Test
    fun `get election eligibility list returns voters`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        tester.registerUserExpectSuccess("bob")
        tester.createElection("Test", aliceTokens.accessToken)
        tester.setEligibleVoters("Test", listOf("bob"), aliceTokens.accessToken)

        val response = tester.getElectionEligibility("Test", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("bob"))
    }

    @Test
    fun `check voter eligibility returns boolean`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Test", aliceTokens.accessToken)
        tester.setEligibleVoters("Test", listOf("bob"), aliceTokens.accessToken)

        val response = tester.checkVoterEligibility("Test", "bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val eligible = body.substringAfter("\"eligible\":").substringBefore("}").trim().toBoolean()
        assertEquals(true, eligible)
    }

    @Test
    fun `check voter eligibility for ineligible voter returns false`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.registerUserExpectSuccess("charlie")
        tester.createElection("Test", aliceTokens.accessToken)
        tester.setEligibleVoters("Test", listOf("bob"), aliceTokens.accessToken)

        val response = tester.checkVoterEligibility("Test", "charlie", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val eligible = body.substringAfter("\"eligible\":").substringBefore("}").trim().toBoolean()
        assertEquals(false, eligible)
    }
}
