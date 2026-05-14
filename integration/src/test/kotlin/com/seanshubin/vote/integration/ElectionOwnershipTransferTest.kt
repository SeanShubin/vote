package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElectionOwnershipTransferTest {
    @Test
    fun `owner can transfer election ownership to another user`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        val election = alice.createElection("Favorite Fruit")
        election.transferOwnership("bob")

        assertEquals("bob", election.getDetails().ownerName)

        val transferEvents = testContext.events.ofType<DomainEvent.ElectionOwnerChanged>()
        assertEquals(1, transferEvents.size)
        assertEquals("Favorite Fruit", transferEvents[0].electionName)
        assertEquals("bob", transferEvents[0].newOwnerName)
    }

    @Test
    fun `transfer is rejected when caller is not the owner and not ADMIN`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        testContext.registerUser("charlie")

        val election = alice.createElection("Best Movie")

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.transferElectionOwnership(bob.accessToken, election.name, "charlie")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        assertEquals("alice", election.getDetails().ownerName)
    }

    @Test
    fun `ADMIN can transfer ownership of an election they do not own`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        testContext.registerUser("charlie")
        // Promote bob to ADMIN so he has MANAGE_USERS — the moderation gate.
        alice.setRole("bob", Role.ADMIN)
        val bobAdmin = testContext.reissueToken("bob")

        val election = alice.createElection("Best Color")
        testContext.backend.transferElectionOwnership(bobAdmin.accessToken, election.name, "charlie")
        testContext.backend.synchronize()

        assertEquals("charlie", election.getDetails().ownerName)
    }

    @Test
    fun `transfer to unknown user fails with NOT_FOUND`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Number")

        val ex = assertFailsWith<ServiceException> {
            election.transferOwnership("ghost")
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }

    @Test
    fun `transfer to current owner is a no-op and emits no event`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Drink")

        election.transferOwnership("alice")

        assertEquals("alice", election.getDetails().ownerName)
        val transferEvents = testContext.events.ofType<DomainEvent.ElectionOwnerChanged>()
        assertEquals(0, transferEvents.size)
    }

    @Test
    fun `transfer is rejected on a missing election`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.transferElectionOwnership(alice.accessToken, "Nonexistent", "bob")
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }

    @Test
    fun `ownership transfer replays correctly from the event log`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Snack")
        election.transferOwnership("bob")

        // Re-run synchronize against the same projection — the event applier
        // should be idempotent.
        testContext.backend.synchronize()

        assertEquals("bob", election.getDetails().ownerName)
    }
}
