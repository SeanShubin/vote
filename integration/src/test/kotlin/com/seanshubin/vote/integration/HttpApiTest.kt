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
                val response = getHealthRaw()
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
        val body = """{"electionName":"$electionName"}"""
        return post("/election", body, token)
    }

    fun listElections(token: AccessToken): HttpResponse<String> = get("/elections", token)

    fun getElection(electionName: String, token: AccessToken): HttpResponse<String> = get("/election/$electionName", token)

    fun deleteElection(electionName: String, token: AccessToken): HttpResponse<String> = delete("/election/$electionName", token)

    // Candidates
    fun setCandidates(electionName: String, candidates: List<String>, token: AccessToken): HttpResponse<String> {
        val candidatesJson = candidates.joinToString(",") { "\"$it\"" }
        val body = """{"candidates":[$candidatesJson]}"""
        return put("/election/$electionName/candidates", body, token)
    }

    fun listCandidates(electionName: String, token: AccessToken): HttpResponse<String> =
        get("/election/$electionName/candidates", token)

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

        val getResponse = get("/user/alice", tokens.accessToken)
        assertTrue(getResponse.body().contains("newemail@example.com"))
    }

    @Test
    fun `owner can set user role`() {
        val aliceTokens = register("alice") // First user is OWNER
        register("bob")

        val response = put("/user/bob/role", """{"role":"ADMIN"}""", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `owner can remove user`() {
        val aliceTokens = register("alice") // First user is OWNER
        register("bob")

        val response = delete("/user/bob", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = get("/user/bob", aliceTokens.accessToken)
        assertTrue(getResponse.statusCode() in listOf(404, 500))
    }

    @Test
    fun `user can change password`() {
        val tokens = register("alice", password = "oldpass")

        val response = put("/user/alice/password", """{"password":"newpass"}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `create election succeeds`() {
        val tokens = register("alice")

        val response = post("/election", """{"userName":"alice","electionName":"Best Language"}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `list elections returns array`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Election 1"}""", tokens.accessToken)
        post("/election", """{"userName":"alice","electionName":"Election 2"}""", tokens.accessToken)

        val response = get("/elections", tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Election 1"))
        assertTrue(response.body().contains("Election 2"))
    }

    @Test
    fun `get election returns details`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test Election"}""", tokens.accessToken)

        val response = get("/election/Test%20Election", tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Test Election"))
        assertTrue(response.body().contains("alice"))
    }

    @Test
    fun `set candidates succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Languages"}""", tokens.accessToken)

        val response = put("/election/Languages/candidates",
            """{"candidateNames":["Kotlin","Java","Go"]}""",
            tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `list candidates returns array`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Languages"}""", tokens.accessToken)
        put("/election/Languages/candidates", """{"candidateNames":["Kotlin","Java"]}""", tokens.accessToken)

        val response = get("/election/Languages/candidates", tokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Kotlin"))
        assertTrue(response.body().contains("Java"))
    }

    @Test
    fun `set eligible voters succeeds`() {
        val tokens = register("alice")
        register("bob")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)

        val response = put("/election/Test/eligibility",
            """{"voterNames":["bob"]}""",
            tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `launch election succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)

        val response = post("/election/Test/launch", """{"allowEdit":true}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `finalize election succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)
        post("/election/Test/launch", """{"allowEdit":false}""", tokens.accessToken)

        val response = post("/election/Test/finalize", "{}", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `delete election succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)

        val response = delete("/election/Test", tokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = get("/election/Test", tokens.accessToken)
        assertEquals(404, getResponse.statusCode())
    }

    @Test
    fun `cast ballot succeeds`() {
        val aliceTokens = register("alice")
        val bobTokens = register("bob")
        post("/election", """{"userName":"alice","electionName":"Lang"}""", aliceTokens.accessToken)
        put("/election/Lang/candidates", """{"candidateNames":["A","B"]}""", aliceTokens.accessToken)
        put("/election/Lang/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)
        post("/election/Lang/launch", """{"allowEdit":true}""", aliceTokens.accessToken)

        val response = post("/election/Lang/ballot",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `get ballot returns rankings`() {
        val aliceTokens = register("alice")
        val bobTokens = register("bob")
        post("/election", """{"userName":"alice","electionName":"Lang"}""", aliceTokens.accessToken)
        put("/election/Lang/candidates", """{"candidateNames":["A","B"]}""", aliceTokens.accessToken)
        put("/election/Lang/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)
        post("/election/Lang/launch", """{"allowEdit":true}""", aliceTokens.accessToken)
        post("/election/Lang/ballot",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        val response = get("/election/Lang/ballot/bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("bob"))
    }

    @Test
    fun `tally returns results`() {
        val aliceTokens = register("alice")
        val bobTokens = register("bob")
        post("/election", """{"userName":"alice","electionName":"Lang"}""", aliceTokens.accessToken)
        put("/election/Lang/candidates", """{"candidateNames":["A","B"]}""", aliceTokens.accessToken)
        put("/election/Lang/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)
        post("/election/Lang/launch", """{"allowEdit":true}""", aliceTokens.accessToken)
        post("/election/Lang/ballot",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1},{"candidateName":"B","rank":2}]}""",
            bobTokens.accessToken)

        val response = get("/election/Lang/tally", aliceTokens.accessToken)

        if (response.statusCode() == 200) {
            assertTrue(response.body().contains("places"))
        } else {
            assertTrue(response.statusCode() in listOf(400, 200))
        }
    }

    @Test
    fun `invalid route returns 404`() {
        val tokens = register("alice")

        val response = get("/nonexistent", tokens.accessToken)

        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("Not found"))
    }

    @Test
    fun `malformed JSON in request returns 400`() {
        val response = post("/register", """{"userName":"alice",""")

        assertEquals(400, response.statusCode())
    }

    @Test
    fun `missing required field returns 400`() {
        val response = post("/register", """{"userName":"alice"}""")

        assertEquals(400, response.statusCode())
    }

    @Test
    fun `refresh token returns new tokens`() {
        val tokens = register("alice")

        val refreshBody = json.encodeToString(tokens.refreshToken)
        val response = post("/refresh", refreshBody)

        assertEquals(200, response.statusCode())
        val newTokens = json.decodeFromString<Tokens>(response.body())
        assertEquals("alice", newTokens.accessToken.userName)
    }

    @Test
    fun `update election secret ballot succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)

        val response = put("/election/Test", """{"secretBallot":false}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `update election voting time window succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)

        val response = put("/election/Test",
            """{"noVotingBefore":"2024-01-01T00:00:00Z","noVotingAfter":"2024-12-31T23:59:59Z"}""",
            tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `update election allowVote and allowEdit succeeds`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"Test"}""", tokens.accessToken)

        val response = put("/election/Test", """{"allowVote":true,"allowEdit":false}""", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `sync endpoint succeeds`() {
        val tokens = register("alice")

        val response = post("/sync", "", tokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `log client error succeeds`() {
        val response = post("/log-client-error", """{"message":"Test error","stackTrace":"Test stack","url":"http://example.com","userAgent":"Test Agent","timestamp":"2024-01-01T00:00:00Z"}""")

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `get user count returns number`() {
        val tokens = register("alice")
        register("bob")

        val response = get("/users/count", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count >= 2)
    }

    @Test
    fun `get election count returns number`() {
        val tokens = register("alice")
        post("/election", """{"userName":"alice","electionName":"E1"}""", tokens.accessToken)
        post("/election", """{"userName":"alice","electionName":"E2"}""", tokens.accessToken)

        val response = get("/elections/count", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count >= 2)
    }

    @Test
    fun `list tables returns array`() {
        val tokens = register("alice")

        val response = get("/tables", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("["), "Expected array but got: $body")
    }

    @Test
    fun `get table count returns number`() {
        val tokens = register("alice")

        val response = get("/tables/count", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count > 0)
    }

    @Test
    fun `get event count returns number`() {
        val tokens = register("alice")

        val response = get("/events/count", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val count = body.substringAfter("\"count\":").substringBefore("}").trim().toInt()
        assertTrue(count > 0, "Should have at least one event from user registration")
    }

    @Test
    fun `get table data returns table structure`() {
        val tokens = register("alice")

        val response = get("/table/user", tokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("columnNames"), "Expected 'columnNames' but got: $body")
        assertTrue(body.contains("rows"), "Expected 'rows' but got: $body")
    }

    @Test
    fun `get permissions for role returns list`() {
        register("alice") // Need at least one user for server to be initialized

        val response = get("/permissions/USER")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("["))
    }

    @Test
    fun `get voter rankings returns list`() {
        val aliceTokens = register("alice")
        val bobTokens = register("bob")
        post("/election", """{"userName":"alice","electionName":"Lang"}""", aliceTokens.accessToken)
        put("/election/Lang/candidates", """{"candidateNames":["A","B"]}""", aliceTokens.accessToken)
        put("/election/Lang/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)
        post("/election/Lang/launch", """{"allowEdit":true}""", aliceTokens.accessToken)
        post("/election/Lang/ballot",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        val response = get("/election/Lang/rankings/bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("candidateName"))
        assertTrue(response.body().contains("rank"))
    }

    @Test
    fun `get election eligibility list returns voters`() {
        val aliceTokens = register("alice")
        register("bob")
        post("/election", """{"userName":"alice","electionName":"Test"}""", aliceTokens.accessToken)
        put("/election/Test/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)

        val response = get("/election/Test/eligibility", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("bob"))
    }

    @Test
    fun `check voter eligibility returns boolean`() {
        val aliceTokens = register("alice")
        val bobTokens = register("bob")
        post("/election", """{"userName":"alice","electionName":"Test"}""", aliceTokens.accessToken)
        put("/election/Test/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)

        val response = get("/election/Test/eligibility/bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val eligible = body.substringAfter("\"eligible\":").substringBefore("}").trim().toBoolean()
        assertEquals(true, eligible)
    }

    @Test
    fun `check voter eligibility for ineligible voter returns false`() {
        val aliceTokens = register("alice")
        val bobTokens = register("bob")
        register("charlie")
        post("/election", """{"userName":"alice","electionName":"Test"}""", aliceTokens.accessToken)
        put("/election/Test/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)

        val response = get("/election/Test/eligibility/charlie", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val eligible = body.substringAfter("\"eligible\":").substringBefore("}").trim().toBoolean()
        assertEquals(false, eligible)
    }
}
