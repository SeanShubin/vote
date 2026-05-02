package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.integration.dsl.TestContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Username and email semantics:
 *  - storage preserves the case the user registered with;
 *  - duplicate detection (registration, rename, email change) is case-INSENSITIVE
 *    so "Alice" and "alice" cannot both exist;
 *  - login (by username or email) is case-INSENSITIVE.
 *
 * These tests run against the InMemory backend that ServiceImpl wires up by default.
 */
class CaseSensitivityTest {

    @Test
    fun `registering a username that differs only in case is rejected`() {
        val ctx = TestContext()
        ctx.registerUser(name = "Alice", email = "alice@example.com")

        val ex = assertFailsWith<ServiceException> {
            ctx.registerUser(name = "alice", email = "other@example.com")
        }
        assertEquals(ServiceException.Category.CONFLICT, ex.category)
        assertTrue(ex.message!!.contains("User name already exists"))
    }

    @Test
    fun `registering an email that differs only in case is rejected`() {
        val ctx = TestContext()
        ctx.registerUser(name = "Alice", email = "alice@example.com")

        val ex = assertFailsWith<ServiceException> {
            ctx.registerUser(name = "Bob", email = "ALICE@EXAMPLE.COM")
        }
        assertEquals(ServiceException.Category.CONFLICT, ex.category)
        assertTrue(ex.message!!.contains("Email already exists"))
    }

    @Test
    fun `registration preserves the case the user typed`() {
        val ctx = TestContext()
        ctx.registerUser(name = "Alice", email = "Alice@Example.com")

        val users = ctx.listUsers()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].userName)
        // Email also preserved in case it was registered with.
        val profile = ctx.getUser("Alice")
        assertEquals("Alice@Example.com", profile.email)
    }

    @Test
    fun `login by username is case-insensitive`() {
        val ctx = TestContext()
        ctx.registerUser(name = "Alice", email = "alice@example.com", password = "pw")

        val tokens = ctx.backend.authenticate("ALICE", "pw")
        assertEquals("Alice", tokens.accessToken.userName)
    }

    @Test
    fun `login by email is case-insensitive`() {
        val ctx = TestContext()
        ctx.registerUser(name = "Alice", email = "alice@example.com", password = "pw")

        val tokens = ctx.backend.authenticate("ALICE@EXAMPLE.COM", "pw")
        assertEquals("Alice", tokens.accessToken.userName)
    }

    @Test
    fun `casting a ballot with mixed-case voter name maps to the canonical user`() {
        val ctx = TestContext()
        val alice = ctx.registerUser(name = "Alice", email = "alice@example.com")
        val election = alice.createElection("Test Election")
        election.setCandidates("Kotlin", "Rust")

        // Voter name in the body is "alice" (different case than the canonical "Alice")
        // but the token belongs to Alice, so the identity check should pass.
        ctx.backend.castBallot(
            alice.accessToken,
            "alice",
            "Test Election",
            listOf(com.seanshubin.vote.domain.Ranking("Kotlin", 1)),
        )
        ctx.backend.synchronize()

        // Ballot lookup by either case finds the same ballot.
        val byCanonical = ctx.backend.getBallot(alice.accessToken, "Alice", "Test Election")
        val byLower = ctx.backend.getBallot(alice.accessToken, "alice", "Test Election")
        assertNotNull(byCanonical)
        assertNotNull(byLower)
        assertEquals(byCanonical.confirmation, byLower.confirmation)
        // The ballot's voter_name is the canonical case, not what the caller passed.
        assertEquals("Alice", byCanonical.voterName)
    }

    @Test
    fun `renaming yourself to a different case of your own name is allowed`() {
        val ctx = TestContext()
        val alice = ctx.registerUser(name = "Alice", email = "alice@example.com")

        // Case-only change of own name should not collide with the existing record.
        alice.updateUser(newName = "ALICE")

        val users = ctx.listUsers()
        assertEquals(1, users.size)
        assertEquals("ALICE", users[0].userName)
    }

    @Test
    fun `renaming to a name owned by another user is rejected even with different case`() {
        val ctx = TestContext()
        ctx.registerUser(name = "Alice", email = "alice@example.com")
        val bob = ctx.registerUser(name = "Bob", email = "bob@example.com")

        val ex = assertFailsWith<IllegalArgumentException> {
            bob.updateUser(newName = "ALICE")
        }
        assertTrue(ex.message!!.contains("User name already exists"))
    }
}
