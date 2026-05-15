package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElectionManagerTest {
    @Test
    fun `owner can add a manager and it shows up in election details`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Favorite Fruit")

        election.addManager("bob")

        assertEquals(listOf("bob"), election.getDetails().managers)
        val events = testContext.events.ofType<DomainEvent.ElectionManagerAdded>()
        assertEquals(1, events.size)
        assertEquals("Favorite Fruit", events[0].electionName)
        assertEquals("bob", events[0].userName)
    }

    @Test
    fun `a manager can edit candidates on an election they do not own`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Movie")
        election.addManager("bob")

        // bob is a plain VOTER who only manages this election — the per-election
        // manager grant, not a global role, is what authorizes the edit.
        testContext.backend.addCandidates(bob.accessToken, election.name, listOf("Dune", "Heat"))
        testContext.backend.synchronize()

        assertEquals(listOf("Dune", "Heat"), election.candidates.sorted())
    }

    @Test
    fun `a manager can remove a candidate and set tiers`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Color")
        election.addManager("bob")
        election.addCandidates("Red", "Blue", "Green")

        // All content edits route through the same requireCanManageElection
        // gate; exercising remove + setTiers as a manager covers it.
        testContext.backend.removeCandidate(bob.accessToken, election.name, "Red")
        testContext.backend.setTiers(bob.accessToken, election.name, listOf("Great", "Okay"))
        testContext.backend.synchronize()

        assertEquals(listOf("Blue", "Green"), election.candidates.sorted())
        assertEquals(listOf("Great", "Okay"), election.getDetails().tiers)
    }

    @Test
    fun `a non-manager non-owner is rejected from editing candidates`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val charlie = testContext.registerUser("charlie")
        val election = alice.createElection("Best Number")
        election.addManager("bob")

        // charlie is neither owner, manager, nor ADMIN.
        val ex = assertFailsWith<ServiceException> {
            testContext.backend.addCandidates(charlie.accessToken, election.name, listOf("42"))
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `owner can remove a manager and the removed user loses edit access`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Snack")
        election.addManager("bob")
        election.removeManager("bob")

        assertEquals(emptyList(), election.getDetails().managers)
        assertEquals(1, testContext.events.ofType<DomainEvent.ElectionManagerRemoved>().size)

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.addCandidates(bob.accessToken, election.name, listOf("Chips"))
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `a manager cannot add or remove other managers`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        testContext.registerUser("charlie")
        val election = alice.createElection("Best Drink")
        election.addManager("bob")

        val addEx = assertFailsWith<ServiceException> {
            testContext.backend.addElectionManager(bob.accessToken, election.name, "charlie")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, addEx.category)

        val removeEx = assertFailsWith<ServiceException> {
            testContext.backend.removeElectionManager(bob.accessToken, election.name, "bob")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, removeEx.category)
    }

    @Test
    fun `a manager cannot delete or transfer the election`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        testContext.registerUser("charlie")
        val election = alice.createElection("Best Pet")
        election.addManager("bob")

        val deleteEx = assertFailsWith<ServiceException> {
            testContext.backend.deleteElection(bob.accessToken, election.name)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, deleteEx.category)

        val transferEx = assertFailsWith<ServiceException> {
            testContext.backend.transferElectionOwnership(bob.accessToken, election.name, "charlie")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, transferEx.category)
    }

    @Test
    fun `adding the owner as a manager is rejected with CONFLICT`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Game")

        val ex = assertFailsWith<ServiceException> { election.addManager("alice") }
        assertEquals(ServiceException.Category.CONFLICT, ex.category)
    }

    @Test
    fun `adding an unknown user as a manager fails with NOT_FOUND`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Book")

        val ex = assertFailsWith<ServiceException> { election.addManager("ghost") }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }

    @Test
    fun `adding an already-existing manager is an idempotent no-op`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Song")

        election.addManager("bob")
        election.addManager("bob")

        assertEquals(listOf("bob"), election.getDetails().managers)
        assertEquals(1, testContext.events.ofType<DomainEvent.ElectionManagerAdded>().size)
    }

    @Test
    fun `removing a user who is not a manager is an idempotent no-op`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Sport")

        election.removeManager("bob")

        assertEquals(0, testContext.events.ofType<DomainEvent.ElectionManagerRemoved>().size)
    }

    @Test
    fun `an ADMIN can add a manager to an election they do not own`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        testContext.registerUser("charlie")
        alice.setRole("bob", Role.ADMIN)
        val bobAdmin = testContext.reissueToken("bob")
        val election = alice.createElection("Best Holiday")

        testContext.backend.addElectionManager(bobAdmin.accessToken, election.name, "charlie")
        testContext.backend.synchronize()

        assertEquals(listOf("charlie"), election.getDetails().managers)
    }

    @Test
    fun `manager grants replay correctly from the event log`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Season")
        election.addManager("bob")

        // Re-run synchronize against the same projection — the event applier
        // should be idempotent.
        testContext.backend.synchronize()

        assertEquals(listOf("bob"), election.getDetails().managers)
    }

    @Test
    fun `removing a manager user from the system drops them from manager lists`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val bob = testContext.registerUser("bob")
        val election = alice.createElection("Best Planet")
        election.addManager("bob")
        assertEquals(listOf("bob"), election.getDetails().managers)

        // alice (OWNER) removes bob from the system entirely.
        alice.removeUser("bob")

        assertTrue(election.getDetails().managers.isEmpty())
    }
}
