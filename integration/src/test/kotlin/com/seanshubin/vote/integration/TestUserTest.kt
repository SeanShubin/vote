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
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `test-domain registration succeeds with shared password`() {
        // Bootstrap an OWNER first so subsequent users register as USER, mirroring real usage.
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val response = tester.registerUser("alice", "alice@one.test", "test")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `test-domain registration rejects a non-shared password`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val response = tester.registerUser("mallory", "mallory@evil.test", "hunter2")
        assertEquals(401, response.statusCode())
    }

    @Test
    fun `real-domain registration accepts any non-empty password (existing behavior)`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val response = tester.registerUser("bob", "bob@example.com", "bobpass")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `password reset email is suppressed for test-domain user`() {
        tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        tester.registerUserExpectSuccess("alice", "alice@one.test", "test")
        tester.integrations.fakeEmailSender.reset()

        val response = tester.requestPasswordReset("alice")
        assertEquals(200, response.statusCode())

        // TestAwareEmailSender swallowed the call before it reached the fake.
        assertTrue(
            tester.integrations.fakeEmailSender.sent.isEmpty(),
            "expected no email send for .test recipient, got ${tester.integrations.fakeEmailSender.sent}",
        )
    }

    @Test
    fun `password reset email still flows for real-domain user`() {
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
        // OWNER (real) → has MANAGE_USERS via OWNER role.
        val ownerTokens = tester.registerUserExpectSuccess("owner", "owner@example.com", "ownerpass")
        val ownerToken = ownerTokens.accessToken

        // Real user — keeps their data after the wipe.
        val bobTokens = tester.registerUserExpectSuccess("bob", "bob@example.com", "bobpass")
        tester.createElection("RealElection", bobTokens.accessToken)

        // Test user — gets wiped along with their election.
        val aliceTokens = tester.registerUserExpectSuccess("alice", "alice@one.test", "test")
        tester.createElection("TestElection", aliceTokens.accessToken)

        val wipeResp = tester.wipeTestUsers(ownerToken)
        assertEquals(200, wipeResp.statusCode())
        val result = json.decodeFromString<WipeTestUsersResult>(wipeResp.body())
        assertEquals(1, result.usersDeleted)
        assertEquals(1, result.electionsDeleted)

        // Verify the user list no longer mentions alice but still has bob and owner.
        val usersResp = tester.listUsers(ownerToken)
        assertEquals(200, usersResp.statusCode())
        val usersBody = usersResp.body()
        assertTrue(usersBody.contains("\"userName\": \"owner\""), "owner should still exist: $usersBody")
        assertTrue(usersBody.contains("\"userName\": \"bob\""), "bob should still exist: $usersBody")
        assertTrue(!usersBody.contains("\"userName\": \"alice\""), "alice should be gone: $usersBody")

        // Verify alice's election is gone but bob's remains.
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
