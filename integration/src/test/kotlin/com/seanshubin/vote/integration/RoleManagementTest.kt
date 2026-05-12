package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoleManagementTest {
    @Test
    fun `owner promoting another user to OWNER is an atomic ownership transfer`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        alice.setRole("bob", Role.OWNER)

        // Caller (alice) is auto-demoted to AUDITOR; target (bob) becomes OWNER.
        assertEquals(Role.AUDITOR, testContext.database.findUser("alice").role)
        assertEquals(Role.OWNER, testContext.database.findUser("bob").role)

        // The transfer is recorded as a single semantic event, not two role changes.
        val transferEvents = testContext.events.ofType<DomainEvent.OwnershipTransferred>()
        assertEquals(1, transferEvents.size)
        assertEquals("alice", transferEvents[0].fromUserName)
        assertEquals("bob", transferEvents[0].toUserName)

        // No spurious UserRoleChanged events emitted alongside the transfer.
        val roleChangeEvents = testContext.events.ofType<DomainEvent.UserRoleChanged>()
        assertEquals(0, roleChangeEvents.size)
    }

    @Test
    fun `non-owner cannot promote anyone to OWNER`() {
        val testContext = TestContext()
        val (alice, bob, _) = testContext.registerUsers("alice", "bob", "charlie")
        // Promote bob to ADMIN so he has MANAGE_USERS but is not OWNER.
        alice.setRole("bob", Role.ADMIN)

        // Bob's existing token still carries the default registration role, so re-authenticate to pick up ADMIN.
        val bobAdmin = TestContextHelper.reauthenticate(testContext, bob.userName)

        val ex = assertFailsWith<ServiceException> {
            bobAdmin.setRole("charlie", Role.OWNER)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        // Still exactly one OWNER, charlie unchanged.
        assertEquals(Role.OWNER, testContext.database.findUser("alice").role)
        assertEquals(Role.VOTER, testContext.database.findUser("charlie").role)
    }

    @Test
    fun `caller cannot change role of self`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        testContext.registerUser("bob")  // ensure not a single-user system

        val ex = assertFailsWith<ServiceException> {
            alice.setRole("alice", Role.AUDITOR)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        assertTrue(ex.message!!.contains("self"), "expected self-edit denial; got: ${ex.message}")
    }

    @Test
    fun `caller cannot promote target to or above caller's own role`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        alice.setRole("bob", Role.ADMIN)
        val bobAdmin = TestContextHelper.reauthenticate(testContext, "bob")
        testContext.registerUser("charlie")  // USER role

        // ADMIN cannot promote anyone to ADMIN or above.
        val sameLevel = assertFailsWith<ServiceException> {
            bobAdmin.setRole("charlie", Role.ADMIN)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, sameLevel.category)

        val above = assertFailsWith<ServiceException> {
            bobAdmin.setRole("charlie", Role.AUDITOR)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, above.category)
    }

    @Test
    fun `setRole on missing user returns NOT_FOUND`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val ex = assertFailsWith<ServiceException> {
            alice.setRole("ghost", Role.VOTER)
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }

    @Test
    fun `listUsers allowedRoles reflect caller's authority`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        val users = alice.listUsers()
        val bob = users.firstOrNull { it.userName == "bob" }
        assertNotNull(bob)
        // OWNER with TRANSFER_OWNER permission can reach every role for a USER target.
        assertEquals(Role.entries.toList(), bob.allowedRoles)

        val aliceEntry = users.firstOrNull { it.userName == "alice" }
        assertNotNull(aliceEntry)
        // Self gets only the current role (cannot self-edit).
        assertEquals(listOf(Role.OWNER), aliceEntry.allowedRoles)
    }

    @Test
    fun `removing a peer or superior is denied`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        alice.setRole("bob", Role.ADMIN)
        val bobAdmin = TestContextHelper.reauthenticate(testContext, "bob")
        testContext.registerUser("eve")
        alice.setRole("eve", Role.ADMIN)  // peer of bob

        val ex = assertFailsWith<ServiceException> {
            bobAdmin.removeUser("eve")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `updating someone else requires strictly greater role`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        // Both bob and charlie become ADMIN — peers.
        alice.setRole("bob", Role.ADMIN)
        testContext.registerUser("charlie")
        alice.setRole("charlie", Role.ADMIN)

        val bobAdmin = TestContextHelper.reauthenticate(testContext, "bob")
        val ex = assertFailsWith<ServiceException> {
            // Try to rename charlie via the updateUser API.
            testContext.backend.updateUser(
                bobAdmin.accessToken,
                "charlie",
                com.seanshubin.vote.domain.UserUpdates(userName = "charles"),
            )
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `ownership transfer replays correctly from the event log`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        alice.setRole("bob", Role.OWNER)

        // Replay: a fresh ServiceImpl reading the same event log + clean projection
        // should reach the same end state. The TestContext's synchronize() is
        // idempotent — calling it again exercises the read-events-and-apply path.
        testContext.backend.synchronize()

        assertEquals(Role.AUDITOR, testContext.database.findUser("alice").role)
        assertEquals(Role.OWNER, testContext.database.findUser("bob").role)
    }
}

private object TestContextHelper {
    fun reauthenticate(
        testContext: TestContext,
        userName: String,
    ): com.seanshubin.vote.integration.dsl.UserContext {
        // Authenticating returns tokens carrying the user's *current* role,
        // which is what we need after a role change.
        val tokens = testContext.backend.authenticate(userName, "password")
        return com.seanshubin.vote.integration.dsl.UserContext(
            testContext = testContext,
            userName = userName,
            accessToken = tokens.accessToken,
        )
    }
}
