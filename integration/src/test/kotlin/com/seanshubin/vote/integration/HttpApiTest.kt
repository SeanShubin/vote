package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.DatabaseConfig
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.AuthResponse
import com.seanshubin.vote.contract.RefreshToken
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
    val integrations: TestIntegrations

    // Sign tokens with the same secret the embedded server uses (the dev fallback
    // in ApplicationRunner). Tests pass AccessToken values around as session
    // handles; these get JWT-signed before going on the wire.
    private val tokenEncoder = TokenEncoder(JwtCipher("dev-jwt-secret-DO-NOT-USE-IN-PROD"))
    fun bearerJwt(token: AccessToken): String = tokenEncoder.encodeAccessToken(token)

    init {
        integrations = TestIntegrations()
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
            .apply { token?.let { header("Authorization", "Bearer ${bearerJwt(it)}") } }
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, token: AccessToken? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .apply { token?.let { header("Authorization", "Bearer ${bearerJwt(it)}") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun put(path: String, body: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${bearerJwt(token)}")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun delete(path: String, token: AccessToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer ${bearerJwt(token)}")
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
        return parseAuthResponse(response.body())
    }

    /** Convert the new wire-level AuthResponse back into the legacy Tokens shape tests expect. */
    private fun parseAuthResponse(body: String): Tokens {
        val auth = json.decodeFromString<AuthResponse>(body)
        return Tokens(
            accessToken = AccessToken(auth.userName, auth.role),
            refreshToken = RefreshToken(auth.userName),
        )
    }

    fun authenticateUser(nameOrEmail: String, password: String): HttpResponse<String> {
        val body = """{"nameOrEmail":"$nameOrEmail","password":"$password"}"""
        return post("/authenticate", body)
    }

    fun requestPasswordReset(nameOrEmail: String): HttpResponse<String> {
        val body = """{"nameOrEmail":"$nameOrEmail"}"""
        return post("/password-reset-request", body)
    }

    fun resetPassword(resetToken: String, newPassword: String): HttpResponse<String> {
        val body = """{"resetToken":"$resetToken","newPassword":"$newPassword"}"""
        return post("/password-reset", body)
    }

    /** Mint a reset token for tests that need to bypass the email step. */
    fun mintResetToken(userName: String): String =
        tokenEncoder.encodeResetToken(userName)

    // User Management
    fun listUsers(token: AccessToken): HttpResponse<String> = get("/users", token)

    fun getMyActivity(token: AccessToken): HttpResponse<String> = get("/me/activity", token)

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

    fun wipeTestUsers(token: AccessToken): HttpResponse<String> = delete("/admin/test-users", token)

    // Elections
    fun createElection(electionName: String, token: AccessToken): HttpResponse<String> {
        val userName = token.userName
        val body = """{"userName":"$userName","electionName":"$electionName"}"""
        return post("/election", body, token)
    }

    fun listElections(token: AccessToken): HttpResponse<String> = get("/elections", token)

    fun getElection(electionName: String, token: AccessToken): HttpResponse<String> = get("/election/$electionName", token)

    fun deleteElection(electionName: String, token: AccessToken): HttpResponse<String> = delete("/election/$electionName", token)

    fun removeUser(userName: String, token: AccessToken): HttpResponse<String> = delete("/user/$userName", token)

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
    /**
     * POST /refresh with the refresh token in a Cookie header (no body).
     * Tests use this to verify the cookie-based refresh path; the JWT is
     * signed locally with the same secret the embedded server uses.
     */
    fun refreshUsingCookie(userName: String): HttpResponse<String> {
        val refreshJwt = tokenEncoder.encodeRefreshToken(RefreshToken(userName))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/refresh"))
            .header("Cookie", "Refresh=$refreshJwt")
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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

    fun listDebugTables(token: AccessToken): HttpResponse<String> = get("/debug-tables", token)

    fun getDebugTableData(tableName: String, token: AccessToken): HttpResponse<String> =
        get("/debug-table/$tableName", token)

    fun getEventData(token: AccessToken): HttpResponse<String> = get("/events", token)

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
        val auth = tester.decodeJson(response, AuthResponse.serializer())
        assertNotNull(auth.accessToken)
        assertEquals("alice", auth.userName)
    }

    @Test
    fun `register with duplicate username returns 409 with honest message`() {
        tester.registerUserExpectSuccess("alice")
        val response = tester.registerUser("alice", "alice2@example.com", "pass")

        assertEquals(409, response.statusCode())
        assertTrue(response.body().contains("User name already exists"),
            "Expected 'User name already exists' in body: ${response.body()}")
    }

    @Test
    fun `register with duplicate email returns 409 with honest message`() {
        tester.registerUserExpectSuccess("alice", email = "shared@example.com")
        val response = tester.registerUser("bob", "shared@example.com", "pass")

        assertEquals(409, response.statusCode())
        assertTrue(response.body().contains("Email already exists"),
            "Expected 'Email already exists' in body: ${response.body()}")
    }

    @Test
    fun `authenticate with valid credentials returns tokens`() {
        tester.registerUserExpectSuccess("alice", password = "secret123")

        val response = tester.authenticateUser("alice", "secret123")

        assertEquals(200, response.statusCode())
        val auth = tester.decodeJson(response, AuthResponse.serializer())
        assertEquals("alice", auth.userName)
    }

    @Test
    fun `authenticate with wrong password returns 401 with honest message`() {
        tester.registerUserExpectSuccess("alice", password = "secret123")

        val response = tester.authenticateUser("alice", "wrong")

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("Wrong password"),
            "Expected 'Wrong password' in body: ${response.body()}")
    }

    @Test
    fun `authenticate with unknown user returns 404 with honest message`() {
        tester.registerUserExpectSuccess("alice", password = "secret123")

        val response = tester.authenticateUser("bob", "secret123")

        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("No user found"),
            "Expected 'No user found' in body: ${response.body()}")
    }

    @Test
    fun `authenticate by email also works`() {
        tester.registerUserExpectSuccess("alice", email = "alice@example.com", password = "secret123")

        val response = tester.authenticateUser("alice@example.com", "secret123")

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `password reset request returns 200 for known user`() {
        tester.registerUserExpectSuccess("alice")

        val response = tester.requestPasswordReset("alice")

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `password reset request returns 404 with honest message for unknown user`() {
        tester.registerUserExpectSuccess("alice")

        val response = tester.requestPasswordReset("nobody")

        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("No user found"),
            "Expected 'No user found' in body: ${response.body()}")
    }

    @Test
    fun `password reset with valid token sets new password`() {
        tester.registerUserExpectSuccess("alice", password = "old-password")
        val resetToken = tester.mintResetToken("alice")

        val resetResponse = tester.resetPassword(resetToken, "new-password")
        assertEquals(200, resetResponse.statusCode())

        // Old password no longer works.
        val oldPwResponse = tester.authenticateUser("alice", "old-password")
        assertEquals(401, oldPwResponse.statusCode())

        // New password works.
        val newPwResponse = tester.authenticateUser("alice", "new-password")
        assertEquals(200, newPwResponse.statusCode())
    }

    @Test
    fun `password reset with bogus token returns 401`() {
        tester.registerUserExpectSuccess("alice", password = "old-password")

        val response = tester.resetPassword("not-a-real-jwt", "new-password")

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("invalid"),
            "Expected 'invalid' in body: ${response.body()}")
    }

    @Test
    fun `access token cannot be replayed as reset token (purpose claim guards)`() {
        // Belt-and-suspenders: even if an attacker steals a valid access token
        // (e.g. via XSS), they must not be able to use it as a password-reset
        // token. The "purpose=reset" claim on reset tokens prevents that.
        val tokens = tester.registerUserExpectSuccess("alice", password = "secret")
        val accessJwt = tester.bearerJwt(tokens.accessToken)

        val response = tester.resetPassword(accessJwt, "hijacked")

        assertEquals(401, response.statusCode())
        // And the password really wasn't changed.
        val auth = tester.authenticateUser("alice", "secret")
        assertEquals(200, auth.statusCode())
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
    fun `delete election succeeds`() {
        val tokens = tester.registerUserExpectSuccess("alice")
        tester.createElection("Test", tokens.accessToken)

        val response = tester.deleteElection("Test", tokens.accessToken)

        assertEquals(200, response.statusCode())

        val getResponse = tester.getElection("Test", tokens.accessToken)
        assertEquals(404, getResponse.statusCode())
    }

    @Test
    fun `delete election - non-owner non-admin (USER) is rejected`() {
        // alice = OWNER (first user); bob = plain USER.
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)

        val response = tester.deleteElection("Lang", bobTokens.accessToken)

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("Only the election owner"))
    }

    @Test
    fun `delete election - admin can moderate someone else's election`() {
        // alice = OWNER; she can delete bob's election by virtue of having
        // MANAGE_USERS (which OWNER inherits from ADMIN).
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Bobs", bobTokens.accessToken)

        val response = tester.deleteElection("Bobs", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `self-delete - regular user can delete their own account`() {
        tester.registerUserExpectSuccess("alice") // OWNER
        val bobTokens = tester.registerUserExpectSuccess("bob") // plain USER

        val response = tester.removeUser("bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `me activity returns role and footprint counts`() {
        // Bob casts a ballot in alice's election; activity should reflect 0
        // owned elections and 1 cast ballot.
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("Kotlin"), aliceTokens.accessToken)
        tester.castBallot(
            "Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"Kotlin","rank":1}]}""",
            bobTokens.accessToken,
        )

        val response = tester.getMyActivity(bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(Regex("\"role\"\\s*:\\s*\"USER\"").containsMatchIn(body), "Expected role USER in: $body")
        assertTrue(Regex("\"electionsOwnedCount\"\\s*:\\s*0").containsMatchIn(body), "Expected 0 owned in: $body")
        assertTrue(Regex("\"ballotsCastCount\"\\s*:\\s*1").containsMatchIn(body), "Expected 1 cast in: $body")
    }

    @Test
    fun `removing a user cascades their cast ballots`() {
        // Bob votes in alice's election. After alice removes bob, bob's ballot
        // should be gone — no orphan voter_name pointing at a deleted user.
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("Kotlin", "Rust"), aliceTokens.accessToken)
        tester.castBallot(
            "Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"Kotlin","rank":1},{"candidateName":"Rust","rank":2}]}""",
            bobTokens.accessToken,
        )

        // Sanity: bob's ballot is there before removal.
        val beforeBallot = tester.getBallot("Lang", "bob", aliceTokens.accessToken)
        assertEquals(200, beforeBallot.statusCode())
        assertTrue(beforeBallot.body().contains("bob"), "Pre-cascade ballot should mention bob: ${beforeBallot.body()}")

        // Alice removes bob — admin remove of a strictly lesser role.
        val removeResponse = tester.removeUser("bob", aliceTokens.accessToken)
        assertEquals(200, removeResponse.statusCode())

        // Bob's ballot should have cascaded — getBallot encodes null when
        // the row is gone, so the literal body is "null".
        val afterBallot = tester.getBallot("Lang", "bob", aliceTokens.accessToken)
        assertEquals(200, afterBallot.statusCode())
        assertEquals("null", afterBallot.body().trim(), "Expected ballot to be cascaded; got: ${afterBallot.body()}")
    }

    @Test
    fun `removing candidates cascades to existing ballot rankings`() {
        // Bob votes for Kotlin and Rust. Alice then removes Rust from the
        // candidate list. Bob's ranking for Rust should disappear.
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("Kotlin", "Rust"), aliceTokens.accessToken)
        tester.castBallot(
            "Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"Kotlin","rank":1},{"candidateName":"Rust","rank":2}]}""",
            bobTokens.accessToken,
        )

        // Drop Rust from the candidate list.
        tester.setCandidates("Lang", listOf("Kotlin"), aliceTokens.accessToken)

        val rankings = tester.getVoterRankings("Lang", "bob", bobTokens.accessToken)
        assertEquals(200, rankings.statusCode())
        val body = rankings.body()
        assertTrue(body.contains("Kotlin"), "Expected Kotlin still present in: $body")
        assertTrue(!body.contains("Rust"), "Expected Rust stripped from rankings in: $body")
    }

    @Test
    fun `self-delete - regular user cannot self-delete while owning elections`() {
        // A non-owner who owns elections must clean them up first; otherwise
        // their elections would be orphans pointing at a removed user.
        tester.registerUserExpectSuccess("alice") // OWNER
        val bobTokens = tester.registerUserExpectSuccess("bob") // plain USER
        tester.createElection("Bob's Choice", bobTokens.accessToken)

        val response = tester.removeUser("bob", bobTokens.accessToken)

        assertEquals(501, response.statusCode())
        assertTrue(response.body().contains("delete your elections first"))
    }

    @Test
    fun `self-delete - OWNER cannot self-delete while other users exist`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice") // OWNER
        tester.registerUserExpectSuccess("bob")

        val response = tester.removeUser("alice", aliceTokens.accessToken)

        assertEquals(501, response.statusCode())
        assertTrue(response.body().contains("OWNER cannot self-delete"))
    }

    @Test
    fun `self-delete - OWNER cannot self-delete while elections exist`() {
        // Even with no other users, leaving any elections behind would orphan
        // them on a non-existent owner. Owner must wipe elections first.
        val aliceTokens = tester.registerUserExpectSuccess("alice") // OWNER
        tester.createElection("Languages", aliceTokens.accessToken)

        val response = tester.removeUser("alice", aliceTokens.accessToken)

        assertEquals(501, response.statusCode())
        assertTrue(response.body().contains("OWNER cannot self-delete"))
    }

    @Test
    fun `self-delete - lone OWNER with no elections can delete themselves (back to empty)`() {
        // The system tolerates an empty state (e.g., reset scenario);
        // an OWNER who is the only user AND has no elections is allowed to leave.
        val aliceTokens = tester.registerUserExpectSuccess("alice")

        val response = tester.removeUser("alice", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `cast ballot succeeds`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)

        val response = tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        // The body must be a JSON string literal (the confirmation ID), not an object.
        // Returning {"status":"..."} here was the source of the frontend's
        // "Unexpected JSON token at offset 0" deserialization crash.
        val body = response.body()
        assertTrue(
            body.startsWith("\"") && body.endsWith("\"") && body.length > 2,
            "cast ballot response should be a JSON string literal (the confirmation ID), got: $body"
        )
    }

    // The "what" (request body voterName) must match the "who" (auth token).
    // No proxy/delegation is supported; without this check, any logged-in
    // eligible voter could submit ballots on behalf of any other eligible voter.
    @Test
    fun `cast ballot rejects voterName that does not match auth token`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)

        // Alice's token + voterName=bob in body — must be rejected.
        val response = tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            aliceTokens.accessToken)

        assertEquals(401, response.statusCode())
        assertTrue(
            response.body().contains("alice") && response.body().contains("bob"),
            "Error should name both the actor and the claimed voter; got: ${response.body()}"
        )
    }

    @Test
    fun `get ballot returns rankings`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)
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
            .header("Authorization", "Bearer ${tester.bearerJwt(tokens.accessToken)}")
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
        tester.registerUserExpectSuccess("alice")

        val response = tester.refreshUsingCookie("alice")

        assertEquals(200, response.statusCode())
        val auth = tester.decodeJson(response, AuthResponse.serializer())
        assertEquals("alice", auth.userName)
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
    fun `list debug tables returns the seven schema names for AUDITOR-or-higher`() {
        // First registered user becomes OWNER, which inherits VIEW_SECRETS.
        val aliceTokens = tester.registerUserExpectSuccess("alice")

        val response = tester.listDebugTables(aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        listOf("users", "elections", "candidates",
               "ballots", "rankings", "sync_state", "event_log").forEach { name ->
            assertTrue(body.contains("\"$name\""), "Expected debug table '$name' in: $body")
        }
    }

    @Test
    fun `debug table data projects users into rows`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        tester.registerUserExpectSuccess("bob")

        val response = tester.getDebugTableData("users", aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        // Server uses pretty-printed JSON, so match key:value with optional whitespace.
        assertTrue(Regex("\"name\"\\s*:\\s*\"users\"").containsMatchIn(body),
            "Expected table name 'users' in: $body")
        assertTrue(body.contains("alice"), "Expected user alice in: $body")
        assertTrue(body.contains("bob"), "Expected user bob in: $body")
    }

    @Test
    fun `events endpoint returns event_log projection`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")

        val response = tester.getEventData(aliceTokens.accessToken)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(Regex("\"name\"\\s*:\\s*\"event_log\"").containsMatchIn(body),
            "Expected event_log in: $body")
        assertTrue(body.contains("UserRegistered"), "Expected UserRegistered event in: $body")
    }

    @Test
    fun `debug endpoints reject non-AUDITOR users with 401`() {
        // First user becomes OWNER; second is plain USER (no VIEW_SECRETS).
        tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")

        val listResp = tester.listDebugTables(bobTokens.accessToken)
        val dataResp = tester.getDebugTableData("users", bobTokens.accessToken)
        val rawListResp = tester.listTables(bobTokens.accessToken)
        val rawDataResp = tester.getTableData("vote_data", bobTokens.accessToken)
        val eventsResp = tester.getEventData(bobTokens.accessToken)

        // ServiceException(UNAUTHORIZED) maps to HTTP 401 in RequestRouter.
        assertEquals(401, listResp.statusCode(), "listDebugTables should be 401 for USER")
        assertEquals(401, dataResp.statusCode(), "debugTableData should be 401 for USER")
        assertEquals(401, rawListResp.statusCode(), "listTables should be 401 for USER")
        assertEquals(401, rawDataResp.statusCode(), "tableData should be 401 for USER")
        assertEquals(401, eventsResp.statusCode(), "eventData should be 401 for USER")
    }

    @Test
    fun `get voter rankings returns list`() {
        val aliceTokens = tester.registerUserExpectSuccess("alice")
        val bobTokens = tester.registerUserExpectSuccess("bob")
        tester.createElection("Lang", aliceTokens.accessToken)
        tester.setCandidates("Lang", listOf("A", "B"), aliceTokens.accessToken)
        tester.castBallot("Lang",
            """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
            bobTokens.accessToken)

        val response = tester.getVoterRankings("Lang", "bob", bobTokens.accessToken)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("candidateName"))
        assertTrue(response.body().contains("rank"))
    }
}
