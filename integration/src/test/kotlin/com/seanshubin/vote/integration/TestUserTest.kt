package com.seanshubin.vote.integration

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.WipeTestUsersResult
import com.seanshubin.vote.domain.Role
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test users are designated by registering with the public marker password
 * "test". The convention is intentionally documented: anyone can mint a
 * test account, and the defense is the wipe endpoint at
 * DELETE /admin/test-users that removes them in bulk.
 */
class TestUserTest {
    private lateinit var tester: HttpApiTester
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        tester = HttpApiTester(port = 9881)
    }

    @AfterEach
    fun teardown() {
        tester.close()
    }

    @Test
    fun `registration accepts the marker password with no email`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val response = tester.registerUser("alice", "", "test")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `registration accepts the marker password with an email`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val response = tester.registerUser("alice", "alice@example.com", "test")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `registration accepts any non-empty password (existing behavior)`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val response = tester.registerUser("bob", "bob@example.com", "bobpass")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `password reset is refused for a test user even if they have an email`() {
        // Edge case: a test user (password=marker) registered with a real
        // email. The service refuses the reset before it reaches the email
        // sender, so no real outbound mail is generated for an account whose
        // password is publicly known.
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        tester.registerUserExpectSuccess("alice", "alice@example.com", "test")
        tester.integrations.fakeEmailSender.reset()

        val response = tester.requestPasswordReset("alice")
        // UNSUPPORTED → 501
        assertEquals(501, response.statusCode())
        assertTrue(
            response.body().contains("test user", ignoreCase = true),
            "expected test-user message; got ${response.body()}",
        )
        assertTrue(
            tester.integrations.fakeEmailSender.sent.isEmpty(),
            "expected no email send for test user, got ${tester.integrations.fakeEmailSender.sent}",
        )
    }

    @Test
    fun `password reset still flows for a non-test user`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        tester.registerUserExpectSuccess("bob", "bob@example.com", "bobpass")
        tester.integrations.fakeEmailSender.reset()

        val response = tester.requestPasswordReset("bob")
        assertEquals(200, response.statusCode())

        val sent = tester.integrations.fakeEmailSender.lastSentTo("bob@example.com")
        assertNotNull(sent, "expected reset email for bob@example.com")
        assertTrue(sent.subject.contains("Reset", ignoreCase = true))
    }

    @Test
    fun `wipe deletes test users plus their elections, leaves real ones`() {
        // OWNER (real) — has MANAGE_USERS via OWNER role.
        val ownerTokens = tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val ownerToken = ownerTokens.accessToken

        // Real user — keeps their data after the wipe.
        tester.registerUserExpectSuccess("bob", "bob@example.com", "bobpass")
        tester.setUserRole("bob", "USER", ownerToken)
        val bobTokens = tester.authenticateUserExpectSuccess("bob", "bobpass")
        tester.createElection("RealElection", bobTokens.accessToken)

        // Test user — no email, marker password — gets wiped along with their election.
        tester.registerUserExpectSuccess("alice", "", "test")
        tester.setUserRole("alice", "USER", ownerToken)
        val aliceTokens = tester.authenticateUserExpectSuccess("alice", "test")
        tester.createElection("TestElection", aliceTokens.accessToken)

        val wipeResp = tester.wipeTestUsers(ownerToken)
        assertEquals(200, wipeResp.statusCode())
        val result = json.decodeFromString<WipeTestUsersResult>(wipeResp.body())
        assertEquals(1, result.usersDeleted)
        assertEquals(1, result.electionsDeleted)

        val usersResp = tester.listUsers(ownerToken)
        assertEquals(200, usersResp.statusCode())
        val usersBody = usersResp.body()
        assertTrue(usersBody.contains("\"userName\": \"owner\""), "owner should still exist: $usersBody")
        assertTrue(usersBody.contains("\"userName\": \"bob\""), "bob should still exist: $usersBody")
        assertTrue(!usersBody.contains("\"userName\": \"alice\""), "alice should be gone: $usersBody")

        val aliceElection = tester.getElection("TestElection", ownerToken)
        assertEquals(404, aliceElection.statusCode())
        val bobElection = tester.getElection("RealElection", ownerToken)
        assertEquals(200, bobElection.statusCode())
    }

    @Test
    fun `wipe rejects callers without MANAGE_USERS`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val bobTokens = tester.registerUserExpectSuccess("bob", "bob@example.com", "bobpass")

        val response = tester.wipeTestUsers(bobTokens.accessToken)
        // requirePermission throws UNAUTHORIZED → 401 in this codebase's exception mapping.
        assertEquals(401, response.statusCode())
    }
}
