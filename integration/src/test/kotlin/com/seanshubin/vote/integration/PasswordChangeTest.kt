package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import com.seanshubin.vote.integration.dsl.UserContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordChangeTest {

    @Test
    fun `self-change with right old password rotates the credential`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", password = "old-pw")

        alice.changeMyPassword("old-pw", "new-pw")

        // The new password authenticates; the old one no longer does.
        testContext.backend.authenticate("alice", "new-pw")
        val ex = assertFailsWith<ServiceException> {
            testContext.backend.authenticate("alice", "old-pw")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `self-change with wrong old password is rejected without rotating`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", password = "old-pw")

        val ex = assertFailsWith<ServiceException> {
            alice.changeMyPassword("not-the-old-pw", "new-pw")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        assertTrue(ex.message!!.contains("Old password"), "expected old-password mismatch; got: ${ex.message}")

        // Old password still works; new one was never set.
        testContext.backend.authenticate("alice", "old-pw")
        val ex2 = assertFailsWith<ServiceException> {
            testContext.backend.authenticate("alice", "new-pw")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex2.category)
    }

    @Test
    fun `self-change emits UserPasswordChanged with caller as authority`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", password = "old-pw")

        alice.changeMyPassword("old-pw", "new-pw")

        val events = testContext.events.ofType<DomainEvent.UserPasswordChanged>()
        assertEquals(1, events.size)
        assertEquals("alice", events[0].userName)
    }

    @Test
    fun `admin-set on a strictly-lower user happy path rotates the credential`() {
        val testContext = TestContext()
        // alice is OWNER (first-registered), bob is USER.
        val (alice, _) = testContext.registerUsers("alice", "bob")

        alice.adminSetPassword("bob", "admin-set-pw")

        // The set password authenticates; original password no longer does.
        testContext.backend.authenticate("bob", "admin-set-pw")
        val ex = assertFailsWith<ServiceException> {
            testContext.backend.authenticate("bob", "password")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `admin-set audit-trail authority is the caller`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        alice.adminSetPassword("bob", "admin-set-pw")

        // Authority on the event is alice (the admin), not "system" — the
        // event log records exactly who reset bob's password.
        val authorities = testContext.events.authoritiesOf<DomainEvent.UserPasswordChanged>()
        assertEquals(listOf("alice"), authorities)
    }

    @Test
    fun `admin-set against equal-role target is rejected`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        // Promote bob to ADMIN; then promote a third user charlie also to ADMIN.
        // bob and charlie are now equal role; bob can't act on charlie.
        val charlie = testContext.registerUser("charlie")
        alice.setRole("bob", Role.ADMIN)
        alice.setRole("charlie", Role.ADMIN)
        val bobAdmin = reauthenticate(testContext, "bob")

        val ex = assertFailsWith<ServiceException> {
            bobAdmin.adminSetPassword("charlie", "no-go")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)

        // Charlie's password is unchanged.
        testContext.backend.authenticate("charlie", "password")
    }

    @Test
    fun `admin-set against higher-role target is rejected`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        // alice is OWNER, bob is USER. Promote bob to ADMIN; bob cannot act on alice.
        alice.setRole("bob", Role.ADMIN)
        val bobAdmin = reauthenticate(testContext, "bob")

        val ex = assertFailsWith<ServiceException> {
            bobAdmin.adminSetPassword("alice", "no-go")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        // Alice's password is unchanged.
        testContext.backend.authenticate("alice", "password")
    }

    @Test
    fun `admin-set against self is rejected`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        // Self-targeting via the admin path is rejected — the user has the
        // changeMyPassword path for that, which proves possession of the
        // old password.
        val ex = assertFailsWith<ServiceException> {
            alice.adminSetPassword("alice", "no-go")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        assertTrue(ex.message!!.contains("changeMyPassword"), "expected self-redirect hint; got: ${ex.message}")
        // Alice's password is unchanged.
        testContext.backend.authenticate("alice", "password")
    }

    @Test
    fun `admin-set without MANAGE_USERS permission is rejected`() {
        val testContext = TestContext()
        val (_, bob) = testContext.registerUsers("alice", "bob")
        // bob is USER — he doesn't have MANAGE_USERS even against a NO_ACCESS
        // target. (We register a third user just to have someone for bob to
        // try to act on.)
        testContext.registerUser("charlie")

        val ex = assertFailsWith<ServiceException> {
            bob.adminSetPassword("charlie", "no-go")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        // Charlie's password is unchanged.
        testContext.backend.authenticate("charlie", "password")
    }

    @Test
    fun `admin-set targeting a missing user returns NOT_FOUND`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val ex = assertFailsWith<ServiceException> {
            alice.adminSetPassword("ghost", "no-such-user")
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }

    @Test
    fun `admin-set followed by login proves the new password is in effect`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        alice.adminSetPassword("bob", "admin-set-pw")

        // bob can now log in with the admin-set password. He's expected to
        // change it via changeMyPassword next, but that's policy, not enforced.
        val bobTokens = testContext.backend.authenticate("bob", "admin-set-pw")
        val bob = UserContext(testContext, "bob", bobTokens.accessToken)
        bob.changeMyPassword("admin-set-pw", "bobs-own-pw")
        testContext.backend.authenticate("bob", "bobs-own-pw")
    }

    private fun reauthenticate(testContext: TestContext, userName: String): UserContext {
        val tokens = testContext.backend.authenticate(userName, "password")
        return UserContext(testContext, userName, tokens.accessToken)
    }
}
